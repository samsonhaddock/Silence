/**
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.smssecure.smssecure.crypto.storage.SilencePreKeyStore;
import org.smssecure.smssecure.util.JsonUtils;
import org.smssecure.smssecure.util.Util;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyStore;
import org.whispersystems.libsignal.util.Medium;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

public class PreKeyUtil {

  public static final int BATCH_SIZE = 100;

  public static List<PreKeyRecord> generatePreKeys(Context context, MasterSecret masterSecret) {
    PreKeyStore        preKeyStore    = new SilencePreKeyStore(context, masterSecret);
    List<PreKeyRecord> records        = new LinkedList<>();
    int                preKeyIdOffset = getNextPreKeyId(context);

    for (int i=0;i<BATCH_SIZE;i++) {
      int          preKeyId = (preKeyIdOffset + i) % Medium.MAX_VALUE;
      ECKeyPair    keyPair  = Curve.generateKeyPair();
      PreKeyRecord record   = new PreKeyRecord(preKeyId, keyPair);

      preKeyStore.storePreKey(preKeyId, record);
      records.add(record);
    }

    setNextPreKeyId(context, (preKeyIdOffset + BATCH_SIZE + 1) % Medium.MAX_VALUE);
    return records;
  }

  public static SignedPreKeyRecord generateSignedPreKey(Context context, MasterSecret masterSecret,
                                                        IdentityKeyPair identityKeyPair)
  {
    try {
      SignedPreKeyStore  signedPreKeyStore = new SilencePreKeyStore(context, masterSecret);
      int                signedPreKeyId    = getNextSignedPreKeyId(context);
      ECKeyPair          keyPair           = Curve.generateKeyPair();
      byte[]             signature         = Curve.calculateSignature(identityKeyPair.getPrivateKey(), keyPair.getPublicKey().serialize());
      SignedPreKeyRecord record            = new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);

      signedPreKeyStore.storeSignedPreKey(signedPreKeyId, record);
      setNextSignedPreKeyId(context, (signedPreKeyId + 1) % Medium.MAX_VALUE);

      return record;
    } catch (InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  public static PreKeyRecord generateLastResortKey(Context context, MasterSecret masterSecret) {
    PreKeyStore preKeyStore = new SilencePreKeyStore(context, masterSecret);

    if (preKeyStore.containsPreKey(Medium.MAX_VALUE)) {
      try {
        return preKeyStore.loadPreKey(Medium.MAX_VALUE);
      } catch (InvalidKeyIdException e) {
        Log.w("PreKeyUtil", e);
        preKeyStore.removePreKey(Medium.MAX_VALUE);
      }
    }

    ECKeyPair    keyPair = Curve.generateKeyPair();
    PreKeyRecord record  = new PreKeyRecord(Medium.MAX_VALUE, keyPair);

    preKeyStore.storePreKey(Medium.MAX_VALUE, record);

    return record;
  }

  private static void setNextPreKeyId(Context context, int id) {
    try {
      File             nextFile = new File(getPreKeysDirectory(context), PreKeyIndex.FILE_NAME);
      FileOutputStream fout     = new FileOutputStream(nextFile);
      fout.write(JsonUtils.toJson(new PreKeyIndex(id)).getBytes());
      fout.close();
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
    }
  }

  private static void setNextSignedPreKeyId(Context context, int id) {
    try {
      File             nextFile = new File(getSignedPreKeysDirectory(context), SignedPreKeyIndex.FILE_NAME);
      FileOutputStream fout     = new FileOutputStream(nextFile);
      fout.write(JsonUtils.toJson(new SignedPreKeyIndex(id)).getBytes());
      fout.close();
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
    }
  }

  private static int getNextPreKeyId(Context context) {
    try {
      File nextFile = new File(getPreKeysDirectory(context), PreKeyIndex.FILE_NAME);

      if (!nextFile.exists()) {
        return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
      } else {
        InputStreamReader reader = new InputStreamReader(new FileInputStream(nextFile));
        PreKeyIndex       index  = JsonUtils.fromJson(reader, PreKeyIndex.class);
        reader.close();
        return index.nextPreKeyId;
      }
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
      return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
    }
  }

  private static int getNextSignedPreKeyId(Context context) {
    try {
      File nextFile = new File(getSignedPreKeysDirectory(context), SignedPreKeyIndex.FILE_NAME);

      if (!nextFile.exists()) {
        return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
      } else {
        InputStreamReader reader = new InputStreamReader(new FileInputStream(nextFile));
        SignedPreKeyIndex index  = JsonUtils.fromJson(reader, SignedPreKeyIndex.class);
        reader.close();
        return index.nextSignedPreKeyId;
      }
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
      return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
    }
  }

  private static File getPreKeysDirectory(Context context) {
    return getKeysDirectory(context, SilencePreKeyStore.PREKEY_DIRECTORY);
  }

  private static File getSignedPreKeysDirectory(Context context) {
    return getKeysDirectory(context, SilencePreKeyStore.SIGNED_PREKEY_DIRECTORY);
  }

  private static File getKeysDirectory(Context context, String name) {
    File directory = new File(context.getFilesDir(), name);

    if (!directory.exists())
      directory.mkdirs();

    return directory;
  }

  private static class PreKeyIndex {
    public static final String FILE_NAME = "index.dat";

    @JsonProperty
    private int nextPreKeyId;

    public PreKeyIndex() {}

    public PreKeyIndex(int nextPreKeyId) {
      this.nextPreKeyId = nextPreKeyId;
    }
  }

  private static class SignedPreKeyIndex {
    public static final String FILE_NAME = "index.dat";

    @JsonProperty
    private int nextSignedPreKeyId;

    public SignedPreKeyIndex() {}

    public SignedPreKeyIndex(int nextSignedPreKeyId) {
      this.nextSignedPreKeyId = nextSignedPreKeyId;
    }
  }


}
