package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.app.form.service.ApplicationFormSubmissionCatalog;
import org.simplepoint.app.form.service.ApplicationFormWorkflowCatalog;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.service.ApplicationFormSubmissionService;
import org.simplepoint.plugin.app.form.service.FormSubmissionPreparationService;
import org.simplepoint.plugin.app.form.service.FormSubmissionSnapshotService;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises actual MVC argument binding without relying on javac parameter metadata. */
class ApplicationFormWorkflowHttpTest {
  @Test
  void submissionPreparationLookupAndRetryKeepPageAndVersionIdentity() throws Exception {
    var submissions = mock(ApplicationFormSubmissionService.class);
    var preparation = mock(FormSubmissionPreparationService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new ApplicationFormSubmissionController(submissions, preparation))
            .build();
    String base = "/runtime/applications/app-a/pages/page-a/submissions";
    mvc.perform(
            get(base + "/preparation")
                .param("definitionId", "workflow-a")
                .param("definitionVersion", "2")
                .param("previousSubmissionId", "previous"))
        .andExpect(status().isOk());
    verify(preparation).prepare("app-a", "page-a", "workflow-a", 2, "previous");
    mvc.perform(
            get(base + "/definitions").param("keyword", "审批").param("page", "1").param("size", "5"))
        .andExpect(status().isOk());
    verify(submissions).definitions("app-a", "page-a", "审批", 1, 5);
    mvc.perform(get(base + "/submission-a")).andExpect(status().isOk());
    verify(submissions).get("app-a", "page-a", "submission-a");
    mvc.perform(post(base + "/submission-a/retry")).andExpect(status().isOk());
    verify(submissions).retry("app-a", "page-a", "submission-a");
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"runtime":{"formId":"form-a","formVersion":1},"idempotencyKey":"request-1",
                     "definitionId":"workflow-a","definitionVersion":2,"title":"提交","values":{},"variables":{}}
                    """))
        .andExpect(status().isOk());
    var command = ArgumentCaptor.forClass(SubmitApplicationFormCommand.class);
    verify(submissions).submit(eq("app-a"), eq("page-a"), command.capture());
    assertThat(command.getValue().idempotencyKey()).isEqualTo("request-1");
    mvc.perform(
            get(base + "/preparation")
                .param("definitionId", "workflow-a")
                .param("definitionVersion", "invalid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void catalogAndSnapshotEndpointsBindTheirExplicitExternalNames() throws Exception {
    var catalog = mock(ApplicationFormWorkflowCatalog.class);
    var submissions = mock(ApplicationFormSubmissionCatalog.class);
    var snapshots = mock(FormSubmissionSnapshotService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new ApplicationFormWorkflowCatalogController(catalog),
                new ApplicationFormSubmissionCatalogController(submissions),
                new ApplicationFormWorkflowSnapshotController(snapshots))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    mvc.perform(get("/workflow/forms")).andExpect(status().isOk());
    verify(catalog).forms("", 0, 20);
    mvc.perform(get("/workflow/forms/options").param("ids", "a:1", "b:2"))
        .andExpect(status().isOk());
    verify(catalog).options(List.of("a:1", "b:2"));
    mvc.perform(get("/workflow/forms/a:1")).andExpect(status().isOk());
    verify(catalog).detail("a:1");
    mvc.perform(get("/workflow/forms/a/versions/2/pages")).andExpect(status().isOk());
    verify(catalog).pages("a", 2, "", 0, 20);
    mvc.perform(get("/workflow/tasks/task-a/business-form")).andExpect(status().isOk());
    verify(snapshots).task("task-a");
    mvc.perform(get("/workflow/instances/instance-a/business-form")).andExpect(status().isOk());
    verify(snapshots).instance("instance-a");
    mvc.perform(get("/runtime/applications/app-a/pages/page-a/submissions/submission-a/snapshot"))
        .andExpect(status().isOk());
    verify(snapshots).owned("app-a", "page-a", "submission-a");
    mvc.perform(get("/runtime/applications/app-a/pages/page-a/submissions/schema"))
        .andExpect(status().isOk());
    verify(submissions).schema("app-a", "page-a");
  }
}
