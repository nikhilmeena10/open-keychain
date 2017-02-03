/*
 * Copyright (C) 2013-2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2016 Vincent Breitmoser <look@my.amazin.horse>
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

package org.sufficientlysecure.keychain.remote;


import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.openintents.openpgp.IOpenPgpService;
import org.openintents.openpgp.OpenPgpDecryptionResult;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.operations.BackupOperation;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogEntryParcel;
import org.sufficientlysecure.keychain.operations.results.PgpSignEncryptResult;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerifyInputParcel;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerifyOperation;
import org.sufficientlysecure.keychain.pgp.PgpSecurityConstants;
import org.sufficientlysecure.keychain.pgp.PgpSignEncryptData;
import org.sufficientlysecure.keychain.pgp.PgpSignEncryptInputParcel;
import org.sufficientlysecure.keychain.pgp.PgpSignEncryptOperation;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.ApiDataAccessObject;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.ApiAccounts;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.remote.OpenPgpServiceKeyIdExtractor.KeyIdResult;
import org.sufficientlysecure.keychain.service.BackupKeyringParcel;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.util.InputData;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

public class OpenPgpService extends Service {
    public static final int API_VERSION_WITH_RESULT_METADATA = 4;
    public static final int API_VERSION_WITH_KEY_REVOKED_EXPIRED = 5;
    public static final int API_VERSION_HIGHEST_WITH_ACCOUNTS = 6;
    public static final int API_VERSION_WITH_KEY_INVALID_INSECURE = 8;
    public static final int API_VERSION_WITHOUT_SIGNATURE_ONLY_FLAG = 8;
    public static final int API_VERSION_WITH_DECRYPTION_RESULT = 8;
    public static final int API_VERSION_WITH_RESULT_NO_SIGNATURE = 8;

    public static final List<Integer> SUPPORTED_VERSIONS =
            Collections.unmodifiableList(Arrays.asList(3, 4, 5, 6, 7, 8, 9, 10, 11));

    private ApiPermissionHelper mApiPermissionHelper;
    private ProviderHelper mProviderHelper;
    private ApiDataAccessObject mApiDao;
    private OpenPgpServiceKeyIdExtractor mKeyIdExtractor;
    private ApiPendingIntentFactory mApiPendingIntentFactory;

    @Override
    public void onCreate() {
        super.onCreate();
        mApiPermissionHelper = new ApiPermissionHelper(this, new ApiDataAccessObject(this));
        mProviderHelper = new ProviderHelper(this);
        mApiDao = new ApiDataAccessObject(this);

        mApiPendingIntentFactory = new ApiPendingIntentFactory(getBaseContext());
        mKeyIdExtractor = OpenPgpServiceKeyIdExtractor.getInstance(getContentResolver(), mApiPendingIntentFactory);
    }

    private Intent signImpl(Intent data, InputStream inputStream,
                            OutputStream outputStream, boolean cleartextSign) {
        try {
            boolean asciiArmor = cleartextSign || data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);

            int targetApiVersion = data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1);

            // sign-only
            PgpSignEncryptData pgpData = new PgpSignEncryptData();
            pgpData.setEnableAsciiArmorOutput(asciiArmor)
                    .setCleartextSignature(cleartextSign)
                    .setDetachedSignature(!cleartextSign)
                    .setVersionHeader(null)
                    .setSignatureHashAlgorithm(PgpSecurityConstants.OpenKeychainHashAlgorithmTags.USE_DEFAULT);


            Intent signKeyIdIntent = getSignKeyMasterId(data);
            // NOTE: Fallback to return account settings (Old API)
            if (signKeyIdIntent.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)
                    == OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED) {
                return signKeyIdIntent;
            }

            long signKeyId = signKeyIdIntent.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, Constants.key.none);
            if (signKeyId == Constants.key.none) {
                throw new Exception("No signing key given");
            } else {
                pgpData.setSignatureMasterKeyId(signKeyId);

                // get first usable subkey capable of signing
                try {
                    long signSubKeyId = mProviderHelper.getCachedPublicKeyRing(
                            pgpData.getSignatureMasterKeyId()).getSecretSignId();
                    pgpData.setSignatureSubKeyId(signSubKeyId);
                } catch (PgpKeyNotFoundException e) {
                    throw new Exception("signing subkey not found!", e);
                }
            }


            PgpSignEncryptInputParcel pseInput = new PgpSignEncryptInputParcel(pgpData);
            pseInput.setAllowedKeyIds(getAllowedKeyIds(targetApiVersion));

            // Get Input- and OutputStream from ParcelFileDescriptor
            if (!cleartextSign) {
                // output stream only needed for cleartext signatures,
                // detached signatures are returned as extra
                outputStream = null;
            }
            long inputLength = inputStream.available();
            InputData inputData = new InputData(inputStream, inputLength);

            CryptoInputParcel inputParcel = CryptoInputParcelCacheService.getCryptoInputParcel(this, data);
            if (inputParcel == null) {
                inputParcel = new CryptoInputParcel(new Date());
            }
            // override passphrase in input parcel if given by API call
            if (data.hasExtra(OpenPgpApi.EXTRA_PASSPHRASE)) {
                inputParcel.mPassphrase =
                        new Passphrase(data.getCharArrayExtra(OpenPgpApi.EXTRA_PASSPHRASE));
            }

            // execute PGP operation!
            PgpSignEncryptOperation pse = new PgpSignEncryptOperation(this, new ProviderHelper(this), null);
            PgpSignEncryptResult pgpResult = pse.execute(pseInput, inputParcel, inputData, outputStream);

            if (pgpResult.isPending()) {
                RequiredInputParcel requiredInput = pgpResult.getRequiredInputParcel();
                PendingIntent pIntent = mApiPendingIntentFactory.requiredInputPi(data,
                        requiredInput, pgpResult.mCryptoInputParcel);

                // return PendingIntent to be executed by client
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_INTENT, pIntent);
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;

            } else if (pgpResult.success()) {
                Intent result = new Intent();
                if (pgpResult.getDetachedSignature() != null && !cleartextSign) {
                    result.putExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE, pgpResult.getDetachedSignature());
                    result.putExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG, pgpResult.getMicAlgDigestName());
                }
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
                return result;
            } else {
                LogEntryParcel errorMsg = pgpResult.getLog().getLast();
                throw new Exception(getString(errorMsg.mType.getMsgId()));
            }
        } catch (Exception e) {
            Log.d(Constants.TAG, "signImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent encryptAndSignImpl(Intent data, InputStream inputStream,
                                      OutputStream outputStream, boolean sign) {
        try {
            boolean asciiArmor = data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
            int targetApiVersion = data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1);
            String originalFilename = data.getStringExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME);
            if (originalFilename == null) {
                originalFilename = "";
            }

            boolean enableCompression = data.getBooleanExtra(OpenPgpApi.EXTRA_ENABLE_COMPRESSION, true);
            int compressionId;
            if (enableCompression) {
                compressionId = PgpSecurityConstants.OpenKeychainCompressionAlgorithmTags.USE_DEFAULT;
            } else {
                compressionId = PgpSecurityConstants.OpenKeychainCompressionAlgorithmTags.UNCOMPRESSED;
            }

            KeyIdResult keyIdResult = mKeyIdExtractor.returnKeyIdsFromIntent(data, false);
            if (keyIdResult.hasResultIntent()) {
                return keyIdResult.getResultIntent();
            }
            long[] keyIds = keyIdResult.getKeyIds();

            // TODO this is not correct!
            long inputLength = inputStream.available();
            InputData inputData = new InputData(inputStream, inputLength, originalFilename);

            PgpSignEncryptData pgpData = new PgpSignEncryptData();
            pgpData.setEnableAsciiArmorOutput(asciiArmor)
                    .setVersionHeader(null)
                    .setCompressionAlgorithm(compressionId)
                    .setSymmetricEncryptionAlgorithm(PgpSecurityConstants.OpenKeychainSymmetricKeyAlgorithmTags.USE_DEFAULT)
                    .setEncryptionMasterKeyIds(keyIds);

            if (sign) {

                Intent signKeyIdIntent = getSignKeyMasterId(data);
                // NOTE: Fallback to return account settings (Old API)
                if (signKeyIdIntent.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)
                        == OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED) {
                    return signKeyIdIntent;
                }
                long signKeyId = signKeyIdIntent.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, Constants.key.none);
                if (signKeyId == Constants.key.none) {
                    throw new Exception("No signing key given");
                } else {
                    pgpData.setSignatureMasterKeyId(signKeyId);

                    // get first usable subkey capable of signing
                    try {
                        long signSubKeyId = mProviderHelper.getCachedPublicKeyRing(
                                pgpData.getSignatureMasterKeyId()).getSecretSignId();
                        pgpData.setSignatureSubKeyId(signSubKeyId);
                    } catch (PgpKeyNotFoundException e) {
                        throw new Exception("signing subkey not found!", e);
                    }
                }

                // sign and encrypt
                pgpData.setSignatureHashAlgorithm(PgpSecurityConstants.OpenKeychainHashAlgorithmTags.USE_DEFAULT)
                        .setAdditionalEncryptId(signKeyId); // add sign key for encryption
            }

            // OLD: Even if the message is not signed: Do self-encrypt to account key id
            if (data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) < 7) {
                String accName = data.getStringExtra(OpenPgpApi.EXTRA_ACCOUNT_NAME);
                // if no account name is given use name "default"
                if (TextUtils.isEmpty(accName)) {
                    accName = "default";
                }
                final AccountSettings accSettings = mApiPermissionHelper.getAccSettings(accName);
                if (accSettings == null || (accSettings.getKeyId() == Constants.key.none)) {
                    return mApiPermissionHelper.getCreateAccountIntent(data, accName);
                }
                pgpData.setAdditionalEncryptId(accSettings.getKeyId());
            }

            PgpSignEncryptInputParcel pseInput = new PgpSignEncryptInputParcel(pgpData);
            pseInput.setAllowedKeyIds(getAllowedKeyIds(targetApiVersion));

            CryptoInputParcel inputParcel = CryptoInputParcelCacheService.getCryptoInputParcel(this, data);
            if (inputParcel == null) {
                inputParcel = new CryptoInputParcel(new Date());
            }
            // override passphrase in input parcel if given by API call
            if (data.hasExtra(OpenPgpApi.EXTRA_PASSPHRASE)) {
                inputParcel.mPassphrase =
                        new Passphrase(data.getCharArrayExtra(OpenPgpApi.EXTRA_PASSPHRASE));
            }

            PgpSignEncryptOperation op = new PgpSignEncryptOperation(this, mProviderHelper, null);

            // execute PGP operation!
            PgpSignEncryptResult pgpResult = op.execute(pseInput, inputParcel, inputData, outputStream);

            if (pgpResult.isPending()) {
                RequiredInputParcel requiredInput = pgpResult.getRequiredInputParcel();
                PendingIntent pIntent = mApiPendingIntentFactory.requiredInputPi(data,
                        requiredInput, pgpResult.mCryptoInputParcel);

                // return PendingIntent to be executed by client
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_INTENT, pIntent);
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;
            } else if (pgpResult.success()) {
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
                return result;
            } else {
                LogEntryParcel errorMsg = pgpResult.getLog().getLast();
                throw new Exception(getString(errorMsg.mType.getMsgId()));
            }
        } catch (Exception e) {
            Log.d(Constants.TAG, "encryptAndSignImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent decryptAndVerifyImpl(Intent data, InputStream inputStream, OutputStream outputStream,
            boolean decryptMetadataOnly, Progressable progressable) {
        try {
            // output is optional, e.g., for verifying detached signatures
            if (decryptMetadataOnly) {
                outputStream = null;
            }

            int targetApiVersion = data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1);

            CryptoInputParcel cryptoInput = CryptoInputParcelCacheService.getCryptoInputParcel(this, data);
            if (cryptoInput == null) {
                cryptoInput = new CryptoInputParcel();
            }
            // override passphrase in input parcel if given by API call
            if (data.hasExtra(OpenPgpApi.EXTRA_PASSPHRASE)) {
                cryptoInput.mPassphrase =
                        new Passphrase(data.getCharArrayExtra(OpenPgpApi.EXTRA_PASSPHRASE));
            }
            if (data.hasExtra(OpenPgpApi.EXTRA_DECRYPTION_RESULT)) {
                OpenPgpDecryptionResult decryptionResult = data.getParcelableExtra(OpenPgpApi.EXTRA_DECRYPTION_RESULT);
                if (decryptionResult != null && decryptionResult.hasDecryptedSessionKey()) {
                    cryptoInput.addCryptoData(decryptionResult.getSessionKey(), decryptionResult.getDecryptedSessionKey());
                }
            }

            byte[] detachedSignature = data.getByteArrayExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE);
            String senderAddress = data.getStringExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS);

            PgpDecryptVerifyOperation op = new PgpDecryptVerifyOperation(this, mProviderHelper, progressable);

            long inputLength = data.getLongExtra(OpenPgpApi.EXTRA_DATA_LENGTH, InputData.UNKNOWN_FILESIZE);
            InputData inputData = new InputData(inputStream, inputLength);

            // allow only private keys associated with accounts of this app
            // no support for symmetric encryption
            PgpDecryptVerifyInputParcel input = new PgpDecryptVerifyInputParcel()
                    .setAllowSymmetricDecryption(false)
                    .setAllowedKeyIds(getAllowedKeyIds(targetApiVersion))
                    .setDecryptMetadataOnly(decryptMetadataOnly)
                    .setDetachedSignature(detachedSignature)
                    .setSenderAddress(senderAddress);

            DecryptVerifyResult pgpResult = op.execute(input, cryptoInput, inputData, outputStream);

            if (pgpResult.isPending()) {
                // prepare and return PendingIntent to be executed by client
                RequiredInputParcel requiredInput = pgpResult.getRequiredInputParcel();
                PendingIntent pIntent = mApiPendingIntentFactory.requiredInputPi(data,
                        requiredInput, pgpResult.mCryptoInputParcel);

                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_INTENT, pIntent);
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;

            } else if (pgpResult.success()) {
                Intent result = new Intent();

                processDecryptionResultForResultIntent(targetApiVersion, result, pgpResult.getDecryptionResult());
                processMetadataForResultIntent(targetApiVersion, result, pgpResult.getDecryptionMetadata());
                processSignatureResultForResultIntent(targetApiVersion, data, result, pgpResult);

                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
                return result;
            } else {
                long[] skippedDisallowedKeys = pgpResult.getSkippedDisallowedKeys();
                if (pgpResult.isKeysDisallowed() && skippedDisallowedKeys.length > 0) {
                    long masterKeyId = skippedDisallowedKeys[0];

                    // allow user to select allowed keys
                    Intent result = new Intent();
                    String packageName = mApiPermissionHelper.getCurrentCallingPackage();
                    result.putExtra(OpenPgpApi.RESULT_INTENT,
                            mApiPendingIntentFactory.createRequestKeyPermissionPendingIntent(
                                    data, packageName, masterKeyId));
                    result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                    return result;
                }

                String errorMsg = getString(pgpResult.getLog().getLast().mType.getMsgId());
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_ERROR, new OpenPgpError(OpenPgpError.GENERIC_ERROR, errorMsg));
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
                return result;
            }

        } catch (Exception e) {
            Log.e(Constants.TAG, "decryptAndVerifyImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR, new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private void processDecryptionResultForResultIntent(int targetApiVersion, Intent result,
            OpenPgpDecryptionResult decryptionResult) {
        if (targetApiVersion < API_VERSION_WITH_DECRYPTION_RESULT) {
            return;
        }

        if (decryptionResult != null) {
            result.putExtra(OpenPgpApi.RESULT_DECRYPTION, decryptionResult);
        }
    }

    private OpenPgpSignatureResult getSignatureResultWithApiCompatibilityFallbacks(
            int targetApiVersion, DecryptVerifyResult pgpResult) {
        OpenPgpSignatureResult signatureResult = pgpResult.getSignatureResult();

        if (targetApiVersion < API_VERSION_WITH_KEY_REVOKED_EXPIRED) {
            // RESULT_INVALID_KEY_REVOKED and RESULT_INVALID_KEY_EXPIRED have been added in version 5
            if (signatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED
                    || signatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED) {
                signatureResult = OpenPgpSignatureResult.createWithInvalidSignature();
            }
        }

        if (targetApiVersion < API_VERSION_WITH_KEY_INVALID_INSECURE) {
            // RESULT_INVALID_INSECURE has been added in version 8, fallback to RESULT_INVALID_SIGNATURE
            if (signatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE) {
                signatureResult = OpenPgpSignatureResult.createWithInvalidSignature();
            }
        }

        if (targetApiVersion < API_VERSION_WITHOUT_SIGNATURE_ONLY_FLAG) {
            // this info was kept in OpenPgpSignatureResult, so put it there for compatibility
            OpenPgpDecryptionResult decryptionResult = pgpResult.getDecryptionResult();
            boolean signatureOnly = decryptionResult.getResult() == OpenPgpDecryptionResult.RESULT_NOT_ENCRYPTED
                    && signatureResult.getResult() != OpenPgpSignatureResult.RESULT_NO_SIGNATURE;
            // noinspection deprecation, this is for backwards compatibility
            signatureResult = signatureResult.withSignatureOnlyFlag(signatureOnly);
        }

        return signatureResult;
    }

    private void processMetadataForResultIntent(int targetApiVersion, Intent result, OpenPgpMetadata metadata) {
        String charset = metadata != null ? metadata.getCharset() : null;
        if (charset != null) {
            result.putExtra(OpenPgpApi.RESULT_CHARSET, charset);
        }

        if (targetApiVersion < API_VERSION_WITH_RESULT_METADATA) {
            return;
        }

        if (metadata != null) {
            result.putExtra(OpenPgpApi.RESULT_METADATA, metadata);
        }
    }

    private void processSignatureResultForResultIntent(int targetApiVersion, Intent data,
            Intent result, DecryptVerifyResult pgpResult) {
        OpenPgpSignatureResult signatureResult =
                getSignatureResultWithApiCompatibilityFallbacks(targetApiVersion, pgpResult);

        switch (signatureResult.getResult()) {
            case OpenPgpSignatureResult.RESULT_KEY_MISSING: {
                // If signature key is missing we return a PendingIntent to retrieve the key
                result.putExtra(OpenPgpApi.RESULT_INTENT,
                        mApiPendingIntentFactory.createImportFromKeyserverPendingIntent(data,
                                signatureResult.getKeyId()));
                break;
            }
            case OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED:
            case OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED:
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED:
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED:
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE: {
                // If signature key is known, return PendingIntent to show key
                result.putExtra(OpenPgpApi.RESULT_INTENT,
                        mApiPendingIntentFactory.createShowKeyPendingIntent(data, signatureResult.getKeyId()));
                break;
            }
            default:
            case OpenPgpSignatureResult.RESULT_NO_SIGNATURE: {
                if (targetApiVersion < API_VERSION_WITH_RESULT_NO_SIGNATURE) {
                    // RESULT_NO_SIGNATURE has been added in version 8, before the signatureResult was null
                    signatureResult = null;
                }
                // otherwise, no pending intent
            }

            case OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE: {
                // no key id -> no PendingIntent
            }
        }

        result.putExtra(OpenPgpApi.RESULT_SIGNATURE, signatureResult);
    }

    private Intent getKeyImpl(Intent data, OutputStream outputStream) {
        try {
            long masterKeyId = data.getLongExtra(OpenPgpApi.EXTRA_KEY_ID, 0);

            try {
                // try to find key, throws NotFoundException if not in db!
                CanonicalizedPublicKeyRing keyRing =
                        mProviderHelper.getCanonicalizedPublicKeyRing(
                                KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(masterKeyId));

                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);

                boolean requestedKeyData = outputStream != null;
                if (requestedKeyData) {
                    boolean requestAsciiArmor = data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, false);

                    try {
                        if (requestAsciiArmor) {
                            outputStream = new ArmoredOutputStream(outputStream);
                        }
                        keyRing.encode(outputStream);
                    } finally {
                        try {
                            outputStream.close();
                        } catch (IOException e) {
                            Log.e(Constants.TAG, "IOException when closing OutputStream", e);
                        }
                    }
                }

                // also return PendingIntent that opens the key view activity
                result.putExtra(OpenPgpApi.RESULT_INTENT,
                        mApiPendingIntentFactory.createShowKeyPendingIntent(data, masterKeyId));

                return result;
            } catch (ProviderHelper.NotFoundException e) {
                // If keys are not in db we return an additional PendingIntent
                // to retrieve the missing key
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_INTENT,
                        mApiPendingIntentFactory.createImportFromKeyserverPendingIntent(data, masterKeyId));
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;
            }
        } catch (Exception e) {
            Log.d(Constants.TAG, "getKeyImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent getSignKeyIdImpl(Intent data) {
        // if data already contains EXTRA_SIGN_KEY_ID, it has been executed again
        // after user interaction. Then, we just need to return the long again!
        if (data.hasExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID)) {
            long signKeyId = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID,
                    Constants.key.none);

            Intent result = new Intent();
            result.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        } else {
            String currentPkg = mApiPermissionHelper.getCurrentCallingPackage();
            String preferredUserId = data.getStringExtra(OpenPgpApi.EXTRA_USER_ID);

            PendingIntent pi = mApiPendingIntentFactory.createSelectSignKeyIdPendingIntent(data, currentPkg, preferredUserId);

            // return PendingIntent to be executed by client
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
            result.putExtra(OpenPgpApi.RESULT_INTENT, pi);

            return result;
        }
    }

    private Intent getKeyIdsImpl(Intent data) {
        KeyIdResult keyIdResult = mKeyIdExtractor.returnKeyIdsFromIntent(data, true);
        if (keyIdResult.hasResultIntent()) {
            return keyIdResult.getResultIntent();
        }
        long[] keyIds = keyIdResult.getKeyIds();

        Intent result = new Intent();
        result.putExtra(OpenPgpApi.RESULT_KEY_IDS, keyIds);
        result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
        return result;
    }

    private Intent backupImpl(Intent data, OutputStream outputStream) {
        try {
            long[] masterKeyIds = data.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS);
            boolean backupSecret = data.getBooleanExtra(OpenPgpApi.EXTRA_BACKUP_SECRET, false);
            boolean enableAsciiArmorOutput = data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);

            CryptoInputParcel inputParcel = CryptoInputParcelCacheService.getCryptoInputParcel(this, data);
            if (inputParcel == null) {
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_INTENT, mApiPendingIntentFactory.createBackupPendingIntent(data, masterKeyIds, backupSecret));
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;
            }
            // after user interaction with RemoteBackupActivity,
            // the backup code is cached in CryptoInputParcelCacheService, now we can proceed

            BackupKeyringParcel input = new BackupKeyringParcel(masterKeyIds, backupSecret, true, enableAsciiArmorOutput, null);
            BackupOperation op = new BackupOperation(this, mProviderHelper, null);
            ExportResult pgpResult = op.execute(input, inputParcel, outputStream);

            if (pgpResult.success()) {
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
                return result;
            } else {
                // should not happen normally...
                String errorMsg = getString(pgpResult.getLog().getLast().mType.getMsgId());
                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_ERROR, new OpenPgpError(OpenPgpError.GENERIC_ERROR, errorMsg));
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
                return result;
            }
        } catch (Exception e) {
            Log.d(Constants.TAG, "backupImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent checkPermissionImpl(@NonNull Intent data) {
        Intent permissionIntent = mApiPermissionHelper.isAllowedOrReturnIntent(data);
        if (permissionIntent != null) {
            return permissionIntent;
        }
        Intent result = new Intent();
        result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
        return result;
    }

    private Intent getSignKeyMasterId(Intent data) {
        // NOTE: Accounts are deprecated on API version >= 7
        if (data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) < 7) {
            String accName = data.getStringExtra(OpenPgpApi.EXTRA_ACCOUNT_NAME);
            // if no account name is given use name "default"
            if (TextUtils.isEmpty(accName)) {
                accName = "default";
            }
            Log.d(Constants.TAG, "accName: " + accName);
            // fallback to old API
            final AccountSettings accSettings = mApiPermissionHelper.getAccSettings(accName);
            if (accSettings == null || (accSettings.getKeyId() == Constants.key.none)) {
                return mApiPermissionHelper.getCreateAccountIntent(data, accName);
            }

            // NOTE: just wrapping the key id
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, accSettings.getKeyId());
            return result;
        } else {
            long signKeyId = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, Constants.key.none);
            if (signKeyId == Constants.key.none) {
                return getSignKeyIdImpl(data);
            }

            return data;
        }
    }

    private HashSet<Long> getAllowedKeyIds(int targetApiVersion) {
        String currentPkg = mApiPermissionHelper.getCurrentCallingPackage();
        HashSet<Long> allowedKeyIds = mApiDao.getAllowedKeyIdsForApp(
                KeychainContract.ApiAllowedKeys.buildBaseUri(currentPkg));

        if (targetApiVersion <= API_VERSION_HIGHEST_WITH_ACCOUNTS) {
            allowedKeyIds.addAll(mApiDao.getAllKeyIdsForApp(
                    ApiAccounts.buildBaseUri(currentPkg)));
        }

        return allowedKeyIds;
    }

    /**
     * Check requirements:
     * - params != null
     * - has supported API version
     * - is allowed to call the service (access has been granted)
     *
     * @return null if everything is okay, or a Bundle with an createErrorPendingIntent/PendingIntent
     */
    private Intent checkRequirements(Intent data) {
        // params Bundle is required!
        if (data == null) {
            Intent result = new Intent();
            OpenPgpError error = new OpenPgpError(OpenPgpError.GENERIC_ERROR, "params Bundle required!");
            result.putExtra(OpenPgpApi.RESULT_ERROR, error);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }

        // version code is required and needs to correspond to version code of service!
        // History of versions in openpgp-api's CHANGELOG.md
        if (!SUPPORTED_VERSIONS.contains(data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1))) {
            Intent result = new Intent();
            OpenPgpError error = new OpenPgpError
                    (OpenPgpError.INCOMPATIBLE_API_VERSIONS, "Incompatible API versions!\n"
                            + "used API version: " + data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) + "\n"
                            + "supported API versions: " + SUPPORTED_VERSIONS);
            result.putExtra(OpenPgpApi.RESULT_ERROR, error);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }

        // check if caller is allowed to access OpenKeychain
        Intent result = mApiPermissionHelper.isAllowedOrReturnIntent(data);
        if (result != null) {
            return result;
        }

        return null;
    }

    private final IOpenPgpService.Stub mBinder = new IOpenPgpService.Stub() {
        @Override
        public Intent execute(Intent data, ParcelFileDescriptor input, ParcelFileDescriptor output) {
            Log.w(Constants.TAG, "You are using a deprecated service which may lead to truncated data on return, please use IOpenPgpService2!");
            return executeInternal(data, input, output);
        }

    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Nullable
    protected Intent executeInternal(
            @NonNull Intent data,
            @Nullable ParcelFileDescriptor input,
            @Nullable ParcelFileDescriptor output) {

        OutputStream outputStream =
                (output != null) ? new ParcelFileDescriptor.AutoCloseOutputStream(output) : null;
        InputStream inputStream =
                (input != null) ? new ParcelFileDescriptor.AutoCloseInputStream(input) : null;

        try {
            return executeInternalWithStreams(data, inputStream, outputStream);
        } finally {
            // always close input and output file descriptors even in createErrorPendingIntent cases
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(Constants.TAG, "IOException when closing input ParcelFileDescriptor", e);
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(Constants.TAG, "IOException when closing output ParcelFileDescriptor", e);
                }
            }
        }
    }

    @Nullable
    protected Intent executeInternalWithStreams(
            @NonNull Intent data,
            @Nullable InputStream inputStream,
            @Nullable OutputStream outputStream) {

        // We need to be able to load our own parcelables
        data.setExtrasClassLoader(getClassLoader());

        Intent errorResult = checkRequirements(data);
        if (errorResult != null) {
            return errorResult;
        }

        Progressable progressable = null;
        if (data.hasExtra(OpenPgpApi.EXTRA_PROGRESS_MESSENGER)) {
            Messenger messenger = data.getParcelableExtra(OpenPgpApi.EXTRA_PROGRESS_MESSENGER);
            progressable = createMessengerProgressable(messenger);
        }

        String action = data.getAction();
        switch (action) {
            case OpenPgpApi.ACTION_CHECK_PERMISSION: {
                return checkPermissionImpl(data);
            }
            case OpenPgpApi.ACTION_CLEARTEXT_SIGN: {
                return signImpl(data, inputStream, outputStream, true);
            }
            case OpenPgpApi.ACTION_SIGN: {
                // DEPRECATED: same as ACTION_CLEARTEXT_SIGN
                Log.w(Constants.TAG, "You are using a deprecated API call, please use ACTION_CLEARTEXT_SIGN instead of ACTION_SIGN!");
                return signImpl(data, inputStream, outputStream, true);
            }
            case OpenPgpApi.ACTION_DETACHED_SIGN: {
                return signImpl(data, inputStream, outputStream, false);
            }
            case OpenPgpApi.ACTION_ENCRYPT: {
                return encryptAndSignImpl(data, inputStream, outputStream, false);
            }
            case OpenPgpApi.ACTION_SIGN_AND_ENCRYPT: {
                return encryptAndSignImpl(data, inputStream, outputStream, true);
            }
            case OpenPgpApi.ACTION_DECRYPT_VERIFY: {
                return decryptAndVerifyImpl(data, inputStream, outputStream, false, progressable);
            }
            case OpenPgpApi.ACTION_DECRYPT_METADATA: {
                return decryptAndVerifyImpl(data, inputStream, outputStream, true, null);
            }
            case OpenPgpApi.ACTION_GET_SIGN_KEY_ID: {
                return getSignKeyIdImpl(data);
            }
            case OpenPgpApi.ACTION_GET_KEY_IDS: {
                return getKeyIdsImpl(data);
            }
            case OpenPgpApi.ACTION_GET_KEY: {
                return getKeyImpl(data, outputStream);
            }
            case OpenPgpApi.ACTION_BACKUP: {
                return backupImpl(data, outputStream);
            }
            default: {
                return null;
            }
        }

    }

    @NonNull
    private static Progressable createMessengerProgressable(final Messenger messenger) {
        return new Progressable() {
            boolean errorState = false;
            @Override
            public void setProgress(String message, int current, int total) {
                setProgress(current, total);
            }

            @Override
            public void setProgress(int resourceId, int current, int total) {
                setProgress(current, total);
            }

            @Override
            public void setProgress(int current, int total) {
                if (errorState) {
                    return;
                }
                Message m = Message.obtain();
                m.arg1 = current;
                m.arg2 = total;
                try {
                    messenger.send(m);
                } catch (RemoteException e) {
                    e.printStackTrace();
                    errorState = true;
                }
            }

            @Override
            public void setPreventCancel() {

            }
        };
    }

}
