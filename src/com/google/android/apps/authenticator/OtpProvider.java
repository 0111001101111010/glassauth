/*
 * Copyright 2010 Google Inc. All Rights Reserved.
 *
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

package com.google.android.apps.authenticator;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.Collection;

import com.google.android.apps.authenticator.AccountDb.OtpType;
import com.google.android.apps.authenticator.PasscodeGenerator.Signer;

/**
 * Class containing implementation of HOTP/TOTP.
 * Generates OTP codes for one or more accounts.
 * @author Steve Weis (sweis@google.com)
 * @author Cem Paya (cemp@google.com)
 */
public class OtpProvider implements OtpSource {

  private static final int PIN_LENGTH = 6; // HOTP or TOTP
  private static final int REFLECTIVE_PIN_LENGTH = 9; // ROTP

  @Override
  public int enumerateAccounts(Collection<String> result) {
    return mAccountDb.getNames(result);
  }

  @Override
  public String getNextCode(String accountName) {
    return getCurrentCode(accountName, null);
  }

  // This variant is used when an additional challenge, such as URL or
  // transaction details, are included in the OTP request.
  // The additional string is appended to standard HOTP/TOTP state before
  // applying the MAC function.
  @Override
  public String respondToChallenge(String accountName, String challenge) {
    if (challenge == null) {
      return getCurrentCode(accountName, null);
    }
    try {
      byte[] challengeBytes = challenge.getBytes("UTF-8");
      return getCurrentCode(accountName, challengeBytes);
    } catch (UnsupportedEncodingException e) {
      return "";
    }
  }

  @Override
  public TotpCounter getTotpCounter() {
    return mTotpCounter;
  }

  private String getCurrentCode(String username, byte[] challenge) {
    // Account name is required.
    if (username == null) {
      return "";
    }

    OtpType type = mAccountDb.getType(username);
    String secret = getSecret(username);

    long otp_state = 0;

    if (type == OtpType.TOTP) {
      // For time-based OTP, the state is derived from clock.
      otp_state =
          mTotpCounter.getValueAtTime(Utilities.millisToSeconds(System.currentTimeMillis()));
    } else if (type == OtpType.HOTP){
      // For counter-based OTP, the state is obtained by incrementing stored counter.
      mAccountDb.incrementCounter(username);
      Integer counter = mAccountDb.getCounter(username);
      otp_state = counter.longValue();
    }

    return computePin(secret, otp_state, challenge);
  }

  public OtpProvider(AccountDb accountDb) {
    this(DEFAULT_INTERVAL, accountDb);
  }

  public OtpProvider(int interval, AccountDb accountDb) {
    mAccountDb = accountDb;
    mTotpCounter = new TotpCounter(interval);
  }

  /**
   * Computes the one-time PIN given the secret key.
   *
   * @param secret the secret key
   * @param otp_state current token state (counter or time-interval)
   * @param challenge optional challenge bytes to include when computing passcode.
   * @return the PIN, or if error an error message
   */
  private String computePin(String secret, long otp_state, byte[] challenge) {
    if (secret == null || secret.length() == 0) {
      return "Null or empty secret";
    }

    try {
      Signer signer = AccountDb.getSigningOracle(secret);
      PasscodeGenerator pcg = new PasscodeGenerator(signer,
        (challenge == null) ? PIN_LENGTH : REFLECTIVE_PIN_LENGTH);

      return (challenge == null) ?
             pcg.generateResponseCode(otp_state) :
             pcg.generateResponseCode(otp_state, challenge);
    } catch (GeneralSecurityException e) {
      return "General security exception";
    }
  }

  /**
   * Reads the secret key that was saved on the phone.
   * @param user Account name identifying the user.
   * @return the secret key as base32 encoded string.
   */
  String getSecret(String user) {
    return mAccountDb.getSecret(user);
  }

  /** Default passcode timeout period (in seconds) */
  public static final int DEFAULT_INTERVAL = 30;

  private final AccountDb mAccountDb;

  /** Counter for time-based OTPs (TOTP). */
  private final TotpCounter mTotpCounter;
}
