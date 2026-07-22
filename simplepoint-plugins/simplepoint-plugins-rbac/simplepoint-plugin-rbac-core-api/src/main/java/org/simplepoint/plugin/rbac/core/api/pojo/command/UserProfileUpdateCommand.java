/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.simplepoint.plugin.rbac.core.api.pojo.command;

import java.time.Instant;
import lombok.Data;

/**
 * Editable fields for the currently authenticated user's profile.
 */
@Data
public class UserProfileUpdateCommand {

  private String nickname;

  private String email;

  private String phoneNumber;

  private String picture;

  private String address;

  private Instant birthdate;

  private String familyName;

  private String givenName;

  private String middleName;

  private String gender;

  private String profile;

  private String website;

  private String locale;

  private String zoneinfo;
}
