/*
 * Copyright 2007 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.result;

import com.google.zxing.Result;

import java.util.regex.Pattern;

/**
 * Implements the "MATMSG" email message entry format.
 *
 * Supported keys: TO, SUB, BODY
 *
 * @author Sean Owen
 */
public final class EmailDoCoMoResultParser extends AbstractDoCoMoResultParser {

  private static final String EMAIL_LOCAL = "[^:]+";
  private static final String EMAIL_DOMAIN = "([0-9a-zA-Z]+[0-9a-zA-Z\\-]+[0-9a-zA-Z]+\\.)+[a-zA-Z]{2,}";
  private static final Pattern EMAIL = Pattern.compile("^" + EMAIL_LOCAL + "@" + EMAIL_DOMAIN + "$");
  
  @Override
  public EmailAddressParsedResult parse(Result result) {
    String rawText = getMassagedText(result);
    if (!rawText.startsWith("MATMSG:")) {
      return null;
    }
    String[] tos = matchDoCoMoPrefixedField("TO:", rawText);
    if (tos == null) {
      return null;
    }
    for (String to : tos) {
      if (!isBasicallyValidEmailAddress(to)) {
        return null;
      }
    }
    String subject = matchSingleDoCoMoPrefixedField("SUB:", rawText, false);
    String body = matchSingleDoCoMoPrefixedField("BODY:", rawText, false);
    return new EmailAddressParsedResult(tos, null, null, subject, body);
  }

  /**
   * This implements only the most basic checking for an email address's validity -- that it contains
   * an '@' and contains no characters disallowed by RFC 2822. This is an overly lenient definition of
   * validity. We want to generally be lenient here since this class is only intended to encapsulate what's
   * in a barcode, not "judge" it.
   */
  static boolean isBasicallyValidEmailAddress(String email) {
    return email != null && EMAIL.matcher(email).matches();
  }

}
