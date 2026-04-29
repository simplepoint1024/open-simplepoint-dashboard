/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.pojo.command;

import java.io.Serializable;
import lombok.Data;

/**
 * Command for changing the authenticated user's password.
 */
@Data
public class ChangePasswordCommand implements Serializable {

  /**
   * The current (old) password for identity verification.
   */
  private String currentPassword;

  /**
   * The new password to set.
   */
  private String newPassword;

  /**
   * Confirmation of the new password; must match newPassword.
   */
  private String confirmPassword;
}
