/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.auth.pac4j;

import org.pac4j.core.exception.CredentialsException;

/**
 * weAccount: the identity provider signed the person in, but they have no access to this
 * application (for example, their access was revoked). {@link ErrorHandler} sends the login page a
 * fixed error code for it, so the page can say so instead of "something went wrong". The message
 * is for the log only.
 */
public class AccessRefusedException extends CredentialsException {

  private static final long serialVersionUID = 1L;

  /** The {@code error} query parameter value the login page recognises. */
  public static final String ERROR_CODE = "access_denied";

  public AccessRefusedException(String message) {
    super(message);
  }
}
