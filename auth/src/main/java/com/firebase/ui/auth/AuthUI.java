/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firebase.ui.auth;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.facebook.login.LoginManager;
import com.firebase.ui.auth.data.model.FlowParameters;
import com.firebase.ui.auth.ui.idp.AuthMethodPickerActivity;
import com.firebase.ui.auth.util.ExtraConstants;
import com.firebase.ui.auth.util.GoogleApiUtils;
import com.firebase.ui.auth.util.Preconditions;
import com.firebase.ui.auth.util.data.PhoneNumberUtils;
import com.firebase.ui.auth.util.data.ProviderAvailability;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.ActionCodeSettings;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GithubAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.auth.TwitterAuthProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import androidx.annotation.CallSuper;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.StringDef;
import androidx.annotation.StyleRes;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.exceptions.ClearCredentialException;

/**
 * The entry point to the AuthUI authentication flow, and related utility methods. If your
 * application uses the default {@link FirebaseApp} instance, an AuthUI instance can be retrieved
 * simply by calling {@link AuthUI#getInstance()}. If an alternative app instance is in use, call
 * {@link AuthUI#getInstance(FirebaseApp)} instead, passing the appropriate app instance.
 * <p>
 * <p>
 * See the
 * <a href="https://github.com/firebase/FirebaseUI-Android/blob/master/auth/README.md#table-of-contents">README</a>
 * for examples on how to get started with FirebaseUI Auth.
 */
public final class AuthUI {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final String TAG = "AuthUI";

    /**
     * Provider for anonymous users.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final String ANONYMOUS_PROVIDER = "anonymous";
    public static final String EMAIL_LINK_PROVIDER = EmailAuthProvider.EMAIL_LINK_SIGN_IN_METHOD;

    public static final String MICROSOFT_PROVIDER = "microsoft.com";
    public static final String YAHOO_PROVIDER = "yahoo.com";
    public static final String APPLE_PROVIDER = "apple.com";

    /**
     * Default value for logo resource, omits the logo from the {@link AuthMethodPickerActivity}.
     */
    public static final int NO_LOGO = -1;

    /**
     * The set of authentication providers supported in Firebase Auth UI.
     */
    public static final Set<String> SUPPORTED_PROVIDERS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    GoogleAuthProvider.PROVIDER_ID,
                    FacebookAuthProvider.PROVIDER_ID,
                    TwitterAuthProvider.PROVIDER_ID,
                    GithubAuthProvider.PROVIDER_ID,
                    EmailAuthProvider.PROVIDER_ID,
                    PhoneAuthProvider.PROVIDER_ID,
                    ANONYMOUS_PROVIDER,
                    EMAIL_LINK_PROVIDER
            )));

    /**
     * The set of OAuth2.0 providers supported in Firebase Auth UI through Generic IDP (web flow).
     */
    public static final Set<String> SUPPORTED_OAUTH_PROVIDERS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    MICROSOFT_PROVIDER,
                    YAHOO_PROVIDER,
                    APPLE_PROVIDER,
                    TwitterAuthProvider.PROVIDER_ID,
                    GithubAuthProvider.PROVIDER_ID
            )));

    /**
     * The set of social authentication providers supported in Firebase Auth UI using their SDK.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final Set<String> SOCIAL_PROVIDERS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    GoogleAuthProvider.PROVIDER_ID,
                    FacebookAuthProvider.PROVIDER_ID)));

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final String UNCONFIGURED_CONFIG_VALUE = "CHANGE-ME";

    private static final IdentityHashMap<FirebaseApp, AuthUI> INSTANCES = new IdentityHashMap<>();

    private static Context sApplicationContext;

    private final FirebaseApp mApp;
    private final FirebaseAuth mAuth;

    private String mEmulatorHost = null;
    private int mEmulatorPort = -1;

    private AuthUI(FirebaseApp app) {
        mApp = app;
        mAuth = FirebaseAuth.getInstance(mApp);

        try {
            mAuth.setFirebaseUIVersion(BuildConfig.VERSION_NAME);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't set the FUI version.", e);
        }
        mAuth.useAppLanguage();
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @NonNull
    public static Context getApplicationContext() {
        return sApplicationContext;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void setApplicationContext(@NonNull Context context) {
        sApplicationContext = Preconditions.checkNotNull(context, "App context cannot be null.")
                .getApplicationContext();
    }

    /**
     * Retrieves the {@link AuthUI} instance associated with the default app, as returned by {@code
     * FirebaseApp.getInstance()}.
     *
     * @throws IllegalStateException if the default app is not initialized.
     */
    @NonNull
    public static AuthUI getInstance() {
        return getInstance(FirebaseApp.getInstance());
    }

    /**
     * Retrieves the {@link AuthUI} instance associated the the specified app name.
     *
     * @throws IllegalStateException if the app is not initialized.
     */
    @NonNull
    public static AuthUI getInstance(@NonNull String appName) {
        return getInstance(FirebaseApp.getInstance(appName));
    }

    /**
     * Retrieves the {@link AuthUI} instance associated the the specified app.
     */
    @NonNull
    public static AuthUI getInstance(@NonNull FirebaseApp app) {
        String releaseUrl = "https://github.com/firebase/FirebaseUI-Android/releases/tag/6.2.0";
        String devWarning = "Beginning with FirebaseUI 6.2.0 you no longer need to include %s to " +
                "sign in with %s. Go to %s for more information";
        if (ProviderAvailability.IS_TWITTER_AVAILABLE) {
            Log.w(TAG, String.format(devWarning, "the TwitterKit SDK", "Twitter", releaseUrl));
        }
        if (ProviderAvailability.IS_GITHUB_AVAILABLE) {
            Log.w(TAG, String.format(devWarning, "com.firebaseui:firebase-ui-auth-github",
                    "GitHub", releaseUrl));
        }

        AuthUI authUi;
        synchronized (INSTANCES) {
            authUi = INSTANCES.get(app);
            if (authUi == null) {
                authUi = new AuthUI(app);
                INSTANCES.put(app, authUi);
            }
        }
        return authUi;
    }

    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public FirebaseApp getApp() {
        return mApp;
    }

    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public FirebaseAuth getAuth() {
        return mAuth;
    }

    /**
     * Returns true if AuthUI can handle the intent.
     * <p>
     * AuthUI handle the intent when the embedded data is an email link. If it is, you can then
     * specify the link in {@link SignInIntentBuilder#setEmailLink(String)} before starting AuthUI
     * and it will be handled immediately.
     */
    public static boolean canHandleIntent(@NonNull Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        String link = intent.getData().toString();
        return FirebaseAuth.getInstance().isSignInWithEmailLink(link);
    }

    /**
     * Default theme used by {@link SignInIntentBuilder#setTheme(int)} if no theme customization is
     * required.
     */
    @StyleRes
    public static int getDefaultTheme() {
        return R.style.FirebaseUI_DefaultMaterialTheme;
    }

    /**
     * Signs the current user out, if one is signed in.
     *
     * @param context the context requesting the user be signed out
     * @return A task which, upon completion, signals that the user has been signed out ({@link
     * Task#isSuccessful()}, or that the sign-out attempt failed unexpectedly !{@link
     * Task#isSuccessful()}).
     */
    @NonNull
    public Task<Void> signOut(@NonNull Context context) {
        boolean playServicesAvailable = GoogleApiUtils.isPlayServicesAvailable(context);
        if (!playServicesAvailable) {
            Log.w(TAG, "Google Play services not available during signOut");
        }
        signOutIdps(context);
        Executor singleThreadExecutor = Executors.newSingleThreadExecutor();
        return clearCredentialState(context, singleThreadExecutor).continueWith(task -> {
            task.getResult(); // Propagate exceptions if any.
            mAuth.signOut();
            return null;
        });
    }

    /**
     * Delete the user from FirebaseAuth.
     *
     * <p>Any associated saved credentials are not explicitly deleted with the new APIs.
     *
     * @param context the calling {@link Context}.
     */
    @NonNull
    public Task<Void> delete(@NonNull final Context context) {
        final FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            return Tasks.forException(new FirebaseAuthInvalidUserException(
                    String.valueOf(CommonStatusCodes.SIGN_IN_REQUIRED),
                    "No currently signed in user."));
        }
        signOutIdps(context);
        Executor singleThreadExecutor = Executors.newSingleThreadExecutor();
        return clearCredentialState(context, singleThreadExecutor).continueWithTask(task -> {
            task.getResult(); // Propagate exceptions if any.
            return currentUser.delete();
        });
    }

    /**
     * Connect to the Firebase Authentication emulator.
     * @see FirebaseAuth#useEmulator(String, int)
     */
    public void useEmulator(@NonNull String host, int port) {
        Preconditions.checkArgument(port >= 0, "Port must be >= 0");
        Preconditions.checkArgument(port <= 65535, "Port must be <= 65535");
        mEmulatorHost = host;
        mEmulatorPort = port;

        mAuth.useEmulator(host, port);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean isUseEmulator() {
        return mEmulatorHost != null && mEmulatorPort >= 0;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public String getEmulatorHost() {
        return mEmulatorHost;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int getEmulatorPort() {
        return mEmulatorPort;
    }

    private void signOutIdps(@NonNull Context context) {
        if (ProviderAvailability.IS_FACEBOOK_AVAILABLE) {
            LoginManager.getInstance().logOut();
        }
    }

    /**
     * A Task to clear the credential state in Credential Manager.
     * @param context
     * @param executor
     * @return
     */
    private Task<Void> clearCredentialState(
            @NonNull Context context,
            @NonNull Executor executor
    ) {
        TaskCompletionSource<Void> completionSource = new TaskCompletionSource<>();

        ClearCredentialStateRequest clearRequest = new ClearCredentialStateRequest();
        GoogleApiUtils.getCredentialManager(context)
                .clearCredentialStateAsync(
                        clearRequest,
                        new CancellationSignal(),
                        executor,
                        new CredentialManagerCallback<>() {
                            @Override
                            public void onResult(Void unused) {
                                completionSource.setResult(unused);
                            }

                            @Override
                            public void onError(@NonNull ClearCredentialException e) {
                                completionSource.setException(e);
                            }
                        }
                );
        return completionSource.getTask();
    }

    /**
     * Starts the process of creating a sign in intent, with the mandatory application context
     * parameter.
     */
    @NonNull
    public SignInIntentBuilder createSignInIntentBuilder() {
        return new SignInIntentBuilder();
    }

    @StringDef({
            GoogleAuthProvider.PROVIDER_ID,
            FacebookAuthProvider.PROVIDER_ID,
            TwitterAuthProvider.PROVIDER_ID,
            GithubAuthProvider.PROVIDER_ID,
            EmailAuthProvider.PROVIDER_ID,
            PhoneAuthProvider.PROVIDER_ID,
            ANONYMOUS_PROVIDER,
            EmailAuthProvider.EMAIL_LINK_SIGN_IN_METHOD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SupportedProvider {
    }

    /**
     * Configuration for an identity provider.
     */
    public static final class IdpConfig implements Parcelable {
        public static final Creator<IdpConfig> CREATOR = new Creator<IdpConfig>() {
            @Override
            public IdpConfig createFromParcel(Parcel in) {
                return new IdpConfig(in);
            }

            @Override
            public IdpConfig[] newArray(int size) {
                return new IdpConfig[size];
            }
        };

        private final String mProviderId;
        private final Bundle mParams;

        private IdpConfig(
                @SupportedProvider @NonNull String providerId,
                @NonNull Bundle params) {
            mProviderId = providerId;
            mParams = new Bundle(params);
        }

        private IdpConfig(Parcel in) {
            mProviderId = in.readString();
            mParams = in.readBundle(getClass().getClassLoader());
        }

        @NonNull
        @SupportedProvider
        public String getProviderId() {
            return mProviderId;
        }

        /**
         * @return provider-specific options
         */
        @NonNull
        public Bundle getParams() {
            return new Bundle(mParams);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int i) {
            parcel.writeString(mProviderId);
            parcel.writeBundle(mParams);
        }

        @Override
        public final boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            IdpConfig config = (IdpConfig) o;

            return mProviderId.equals(config.mProviderId);
        }

        @Override
        public final int hashCode() {
            return mProviderId.hashCode();
        }

        @Override
        public String toString() {
            return "IdpConfig{" +
                    "mProviderId='" + mProviderId + '\'' +
                    ", mParams=" + mParams +
                    '}';
        }

        /**
         * Base builder for all authentication providers.
         *
         * @see SignInIntentBuilder#setAvailableProviders(List)
         */
        public static class Builder {
            private final Bundle mParams = new Bundle();
            @SupportedProvider
            private String mProviderId;

            protected Builder(@SupportedProvider @NonNull String providerId) {
                if (!SUPPORTED_PROVIDERS.contains(providerId)
                        && !SUPPORTED_OAUTH_PROVIDERS.contains(providerId)) {
                    throw new IllegalArgumentException("Unknown provider: " + providerId);
                }
                mProviderId = providerId;
            }

            @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
            @NonNull
            protected final Bundle getParams() {
                return mParams;
            }

            @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
            protected void setProviderId(@NonNull String providerId) {
                mProviderId = providerId;
            }

            @CallSuper
            @NonNull
            public IdpConfig build() {
                return new IdpConfig(mProviderId, mParams);
            }
        }

        /**
         * {@link IdpConfig} builder for the email provider.
         */
        public static final class EmailBuilder extends Builder {
            public EmailBuilder() {
                super(EmailAuthProvider.PROVIDER_ID);
            }

            /**
             * Enables or disables creating new accounts in the email sign in flows.
             * <p>
             * Account creation is enabled by default.
             */
            @NonNull
            public EmailBuilder setAllowNewAccounts(boolean allow) {
                getParams().putBoolean(ExtraConstants.ALLOW_NEW_EMAILS, allow);
                return this;
            }

            /**
             * Configures the requirement for the user to enter first and last name in the email
             * sign up flow.
             * <p>
             * Name is required by default.
             */
            @NonNull
            public EmailBuilder setRequireName(boolean requireName) {
                getParams().putBoolean(ExtraConstants.REQUIRE_NAME, requireName);
                return this;
            }

            /**
             * Enables email link sign in instead of password based sign in. Once enabled, you must
             * pass a valid {@link ActionCodeSettings} object using
             * {@link #setActionCodeSettings(ActionCodeSettings)}
             * <p>
             * You must enable Firebase Dynamic Links in the Firebase Console to use email link
             * sign in.
             *
             * @throws IllegalStateException if {@link ActionCodeSettings} is null or not
             *                               provided with email link enabled.
             */
            @NonNull
            public EmailBuilder enableEmailLinkSignIn() {
                setProviderId(EMAIL_LINK_PROVIDER);
                return this;
            }

            /**
             * Sets the {@link ActionCodeSettings} object to be used for email link sign in.
             * <p>
             * {@link ActionCodeSettings#canHandleCodeInApp()} must be set to true, and a valid
             * continueUrl must be passed via {@link ActionCodeSettings.Builder#setUrl(String)}.
             * This URL must be allowlisted in the Firebase Console.
             *
             * @throws IllegalStateException if canHandleCodeInApp is set to false
             * @throws NullPointerException  if ActionCodeSettings is null
             */
            @NonNull
            public EmailBuilder setActionCodeSettings(ActionCodeSettings actionCodeSettings) {
                getParams().putParcelable(ExtraConstants.ACTION_CODE_SETTINGS, actionCodeSettings);
                return this;
            }

            /**
             * Disables allowing email link sign in to occur across different devices.
             * <p>
             * This cannot be disabled with anonymous upgrade.
             */
            @NonNull
            public EmailBuilder setForceSameDevice() {
                getParams().putBoolean(ExtraConstants.FORCE_SAME_DEVICE, true);
                return this;
            }

            /**
             * Sets a default sign in email, if the given email has been registered before, then
             * it will ask the user for password, if the given email it's not registered, then
             * it starts signing up the default email.
             */
            @NonNull
            public EmailBuilder setDefaultEmail(String email) {
                getParams().putString(ExtraConstants.DEFAULT_EMAIL, email);
                return this;
            }

            @Override
            public IdpConfig build() {
                if (super.mProviderId.equals(EMAIL_LINK_PROVIDER)) {
                    ActionCodeSettings actionCodeSettings =
                            getParams().getParcelable(ExtraConstants.ACTION_CODE_SETTINGS);
                    Preconditions.checkNotNull(actionCodeSettings, "ActionCodeSettings cannot be " +
                            "null when using email link sign in.");
                    if (!actionCodeSettings.canHandleCodeInApp()) {
                        // Pre-emptively fail if actionCodeSettings are misconfigured. This would
                        // have happened when calling sendSignInLinkToEmail
                        throw new IllegalStateException(
                                "You must set canHandleCodeInApp in your ActionCodeSettings to " +
                                        "true for Email-Link Sign-in.");
                    }
                }
                return super.build();
            }
        }

        /**
         * {@link IdpConfig} builder for the phone provider.
         */
        public static final class PhoneBuilder extends Builder {
            public PhoneBuilder() {
                super(PhoneAuthProvider.PROVIDER_ID);
            }

            /**
             * @param number the phone number in international format
             * @see #setDefaultNumber(String, String)
             */
            @NonNull
            public PhoneBuilder setDefaultNumber(@NonNull String number) {
                Preconditions.checkUnset(getParams(),
                        "Cannot overwrite previously set phone number",
                        ExtraConstants.PHONE,
                        ExtraConstants.COUNTRY_ISO,
                        ExtraConstants.NATIONAL_NUMBER);
                if (!PhoneNumberUtils.isValid(number)) {
                    throw new IllegalStateException("Invalid phone number: " + number);
                }

                getParams().putString(ExtraConstants.PHONE, number);

                return this;
            }

            /**
             * Set the default phone number that will be used to populate the phone verification
             * sign-in flow.
             *
             * @param iso    the phone number's country code
             * @param number the phone number in local format
             */
            @NonNull
            public PhoneBuilder setDefaultNumber(@NonNull String iso, @NonNull String number) {
                Preconditions.checkUnset(getParams(),
                        "Cannot overwrite previously set phone number",
                        ExtraConstants.PHONE,
                        ExtraConstants.COUNTRY_ISO,
                        ExtraConstants.NATIONAL_NUMBER);
                if (!PhoneNumberUtils.isValidIso(iso)) {
                    throw new IllegalStateException("Invalid country iso: " + iso);
                }

                getParams().putString(ExtraConstants.COUNTRY_ISO, iso);
                getParams().putString(ExtraConstants.NATIONAL_NUMBER, number);

                return this;
            }

            /**
             * Set the default country code that will be used in the phone verification sign-in
             * flow.
             *
             * @param iso country iso
             */
            @NonNull
            public PhoneBuilder setDefaultCountryIso(@NonNull String iso) {
                Preconditions.checkUnset(getParams(),
                        "Cannot overwrite previously set phone number",
                        ExtraConstants.PHONE,
                        ExtraConstants.COUNTRY_ISO,
                        ExtraConstants.NATIONAL_NUMBER);
                if (!PhoneNumberUtils.isValidIso(iso)) {
                    throw new IllegalStateException("Invalid country iso: " + iso);
                }

                getParams().putString(ExtraConstants.COUNTRY_ISO,
                        iso.toUpperCase(Locale.getDefault()));

                return this;
            }


            /**
             * Sets the country codes available in the country code selector for phone
             * authentication. Takes as input a List of both country isos and codes.
             * This is not to be called with
             * {@link #setBlockedCountries(List)}.
             * If both are called, an exception will be thrown.
             * <p>
             * Inputting an e-164 country code (e.g. '+1') will include all countries with
             * +1 as its code.
             * Example input: {'+52', 'us'}
             * For a list of country iso or codes, see Alpha-2 isos here:
             * https://en.wikipedia.org/wiki/ISO_3166-1
             * and e-164 codes here: https://en.wikipedia.org/wiki/List_of_country_calling_codes
             *
             * @param countries a non empty case insensitive list of country codes
             *                  and/or isos to be allowlisted
             * @throws IllegalArgumentException if an empty allowlist is provided.
             * @throws NullPointerException     if a null allowlist is provided.
             */
            public PhoneBuilder setAllowedCountries(
                    @NonNull List<String> countries) {
                if (getParams().containsKey(ExtraConstants.BLOCKLISTED_COUNTRIES)) {
                    throw new IllegalStateException(
                            "You can either allowlist or blocklist country codes for phone " +
                                    "authentication.");
                }

                String message = "Invalid argument: Only non-%s allowlists are valid. " +
                        "To specify no allowlist, do not call this method.";
                Preconditions.checkNotNull(countries, String.format(message, "null"));
                Preconditions.checkArgument(!countries.isEmpty(), String.format
                        (message, "empty"));

                addCountriesToBundle(countries, ExtraConstants.ALLOWLISTED_COUNTRIES);
                return this;
            }

            /**
             * Sets the countries to be removed from the country code selector for phone
             * authentication. Takes as input a List of both country isos and codes.
             * This is not to be called with
             * {@link #setAllowedCountries(List)}.
             * If both are called, an exception will be thrown.
             * <p>
             * Inputting an e-164 country code (e.g. '+1') will include all countries with
             * +1 as its code.
             * Example input: {'+52', 'us'}
             * For a list of country iso or codes, see Alpha-2 codes here:
             * https://en.wikipedia.org/wiki/ISO_3166-1
             * and e-164 codes here: https://en.wikipedia.org/wiki/List_of_country_calling_codes
             *
             * @param countries a non empty case insensitive list of country codes
             *                  and/or isos to be blocklisted
             * @throws IllegalArgumentException if an empty blocklist is provided.
             * @throws NullPointerException     if a null blocklist is provided.
             */
            public PhoneBuilder setBlockedCountries(
                    @NonNull List<String> countries) {
                if (getParams().containsKey(ExtraConstants.ALLOWLISTED_COUNTRIES)) {
                    throw new IllegalStateException(
                            "You can either allowlist or blocklist country codes for phone " +
                                    "authentication.");
                }

                String message = "Invalid argument: Only non-%s blocklists are valid. " +
                        "To specify no blocklist, do not call this method.";
                Preconditions.checkNotNull(countries, String.format(message, "null"));
                Preconditions.checkArgument(!countries.isEmpty(), String.format
                        (message, "empty"));

                addCountriesToBundle(countries, ExtraConstants.BLOCKLISTED_COUNTRIES);
                return this;
            }

            @Override
            public IdpConfig build() {
                validateInputs();
                return super.build();
            }

            private void addCountriesToBundle(List<String> CountryIsos, String CountryIsoType) {
                ArrayList<String> uppercaseCodes = new ArrayList<>();
                for (String code : CountryIsos) {
                    uppercaseCodes.add(code.toUpperCase(Locale.getDefault()));
                }

                getParams().putStringArrayList(CountryIsoType, uppercaseCodes);
            }

            private void validateInputs() {
                List<String> allowedCountries = getParams().getStringArrayList(
                        ExtraConstants.ALLOWLISTED_COUNTRIES);
                List<String> blockedCountries = getParams().getStringArrayList(
                        ExtraConstants.BLOCKLISTED_COUNTRIES);

                if (allowedCountries != null && blockedCountries != null) {
                    throw new IllegalStateException(
                            "You can either allowlist or blocked country codes for phone " +
                                    "authentication.");
                } else if (allowedCountries != null) {
                    validateInputs(allowedCountries, true);

                } else if (blockedCountries != null) {
                    validateInputs(blockedCountries, false);
                }
            }

            private void validateInputs(List<String> countries, boolean allowed) {
                validateCountryInput(countries);
                validateDefaultCountryInput(countries, allowed);
            }

            private void validateCountryInput(List<String> codes) {
                for (String code : codes) {
                    if (!PhoneNumberUtils.isValidIso(code) && !PhoneNumberUtils.isValid(code)) {
                        throw new IllegalArgumentException("Invalid input: You must provide a " +
                                "valid country iso (alpha-2) or code (e-164). e.g. 'us' or '+1'.");
                    }
                }
            }

            private void validateDefaultCountryInput(List<String> codes, boolean allowed) {
                // A default iso/code can be set via #setDefaultCountryIso() or #setDefaultNumber()
                if (getParams().containsKey(ExtraConstants.COUNTRY_ISO) ||
                        getParams().containsKey(ExtraConstants.PHONE)) {

                    if (!validateDefaultCountryIso(codes, allowed)
                            || !validateDefaultPhoneIsos(codes, allowed)) {
                        throw new IllegalArgumentException("Invalid default country iso. Make " +
                                "sure it is either part of the allowed list or that you "
                                + "haven't blocked it.");
                    }
                }

            }

            private boolean validateDefaultCountryIso(List<String> codes, boolean allowed) {
                String defaultIso = getDefaultIso();
                return isValidDefaultIso(codes, defaultIso, allowed);
            }

            private boolean validateDefaultPhoneIsos(List<String> codes, boolean allowed) {
                List<String> phoneIsos = getPhoneIsosFromCode();
                for (String iso : phoneIsos) {
                    if (isValidDefaultIso(codes, iso, allowed)) {
                        return true;
                    }
                }
                return phoneIsos.isEmpty();
            }

            private boolean isValidDefaultIso(List<String> codes, String iso, boolean allowed) {
                if (iso == null) return true;
                boolean containsIso = containsCountryIso(codes, iso);
                return containsIso && allowed || !containsIso && !allowed;

            }

            private boolean containsCountryIso(List<String> codes, String iso) {
                iso = iso.toUpperCase(Locale.getDefault());
                for (String code : codes) {
                    if (PhoneNumberUtils.isValidIso(code)) {
                        if (code.equals(iso)) {
                            return true;
                        }
                    } else {
                        List<String> isos = PhoneNumberUtils.getCountryIsosFromCountryCode(code);
                        if (isos.contains(iso)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private List<String> getPhoneIsosFromCode() {
                List<String> isos = new ArrayList<>();
                String phone = getParams().getString(ExtraConstants.PHONE);
                if (phone != null && phone.startsWith("+")) {
                    String countryCode = "+" + PhoneNumberUtils.getPhoneNumber(phone)
                            .getCountryCode();
                    List<String> isosToAdd = PhoneNumberUtils.
                            getCountryIsosFromCountryCode(countryCode);
                    if (isosToAdd != null) {
                        isos.addAll(isosToAdd);
                    }
                }
                return isos;
            }

            private String getDefaultIso() {
                return getParams().containsKey(ExtraConstants.COUNTRY_ISO) ?
                        getParams().getString(ExtraConstants.COUNTRY_ISO) : null;
            }
        }

        /**
         * {@link IdpConfig} builder for the Google provider.
         */
        public static final class GoogleBuilder extends Builder {
            public GoogleBuilder() {
                super(GoogleAuthProvider.PROVIDER_ID);
            }

            private void validateWebClientId() {
                Preconditions.checkConfigured(getApplicationContext(),
                        "Check your google-services plugin configuration, the" +
                                " default_web_client_id string wasn't populated.",
                        R.string.default_web_client_id);
            }

            /**
             * Set the scopes that your app will request when using Google sign-in. See all <a
             * href="https://developers.google.com/identity/protocols/googlescopes">available
             * scopes</a>.
             *
             * @param scopes additional scopes to be requested
             */
            @NonNull
            public GoogleBuilder setScopes(@NonNull List<String> scopes) {
                GoogleSignInOptions.Builder builder =
                        new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail();
                for (String scope : scopes) {
                    builder.requestScopes(new Scope(scope));
                }
                return setSignInOptions(builder.build());
            }

            /**
             * Set the {@link GoogleSignInOptions} to be used for Google sign-in. Standard
             * options like requesting the user's email will automatically be added.
             *
             * @param options sign-in options
             */
            @NonNull
            public GoogleBuilder setSignInOptions(@NonNull GoogleSignInOptions options) {
                Preconditions.checkUnset(getParams(),
                        "Cannot overwrite previously set sign-in options.",
                        ExtraConstants.GOOGLE_SIGN_IN_OPTIONS);

                GoogleSignInOptions.Builder builder = new GoogleSignInOptions.Builder(options);

                String clientId = options.getServerClientId();
                if (clientId == null) {
                    validateWebClientId();
                    clientId = getApplicationContext().getString(R.string.default_web_client_id);
                }

                // Warn the user that they are _probably_ doing the wrong thing if they
                // have not called requestEmail (see issue #1899 and #1621)
                boolean hasEmailScope = false;
                for (Scope s : options.getScopes()) {
                    if ("email".equals(s.getScopeUri())) {
                        hasEmailScope = true;
                        break;
                    }
                }
                if (!hasEmailScope) {
                    Log.w(TAG, "The GoogleSignInOptions passed to setSignInOptions does not " +
                            "request the 'email' scope. In most cases this is a mistake! " +
                            "Call requestEmail() on the GoogleSignInOptions object.");
                }

                builder.requestIdToken(clientId);
                getParams().putParcelable(
                        ExtraConstants.GOOGLE_SIGN_IN_OPTIONS, builder.build());

                return this;
            }

            @NonNull
            @Override
            public IdpConfig build() {
                if (!getParams().containsKey(ExtraConstants.GOOGLE_SIGN_IN_OPTIONS)) {
                    validateWebClientId();
                    setScopes(Collections.emptyList());
                }

                return super.build();
            }
        }

        /**
         * {@link IdpConfig} builder for the Facebook provider.
         */
        public static final class FacebookBuilder extends Builder {
            private static final String TAG = "FacebookBuilder";

            public FacebookBuilder() {
                super(FacebookAuthProvider.PROVIDER_ID);
                if (!ProviderAvailability.IS_FACEBOOK_AVAILABLE) {
                    throw new RuntimeException(
                            "Facebook provider cannot be configured " +
                                    "without dependency. Did you forget to add " +
                                    "'com.facebook.android:facebook-login:VERSION' dependency?");
                }
                Preconditions.checkConfigured(getApplicationContext(),
                        "Facebook provider unconfigured. Make sure to add a" +
                                " `facebook_application_id` string. See the docs for more info:" +
                                " https://github" +
                                ".com/firebase/FirebaseUI-Android/blob/master/auth/README" +
                                ".md#facebook",
                        R.string.facebook_application_id);
                if (getApplicationContext().getString(R.string.facebook_login_protocol_scheme)
                        .equals("fbYOUR_APP_ID")) {
                    Log.w(TAG, "Facebook provider unconfigured for Chrome Custom Tabs.");
                }
            }

            /**
             * Specifies the additional permissions that the application will request in the
             * Facebook Login SDK. Available permissions can be found <a
             * href="https://developers.facebook.com/docs/facebook-login/permissions">here</a>.
             */
            @NonNull
            public FacebookBuilder setPermissions(@NonNull List<String> permissions) {
                getParams().putStringArrayList(
                        ExtraConstants.FACEBOOK_PERMISSIONS, new ArrayList<>(permissions));
                return this;
            }
        }

        /**
         * {@link IdpConfig} builder for the Anonymous provider.
         */
        public static final class AnonymousBuilder extends Builder {
            public AnonymousBuilder() {
                super(ANONYMOUS_PROVIDER);
            }
        }

        /**
         * {@link IdpConfig} builder for the Twitter provider.
         */
        public static final class TwitterBuilder extends GenericOAuthProviderBuilder {
            private static final String PROVIDER_NAME = "Twitter";

            public TwitterBuilder() {
                super(TwitterAuthProvider.PROVIDER_ID, PROVIDER_NAME,
                        R.layout.fui_idp_button_twitter);
            }
        }

        /**
         * {@link IdpConfig} builder for the GitHub provider.
         */
        public static final class GitHubBuilder extends GenericOAuthProviderBuilder {
            private static final String PROVIDER_NAME = "Github";

            public GitHubBuilder() {
                super(GithubAuthProvider.PROVIDER_ID, PROVIDER_NAME,
                        R.layout.fui_idp_button_github);
            }

            /**
             * Specifies the additional permissions to be requested.
             *
             * <p> Available permissions can be found
             * <ahref="https://developer.github.com/apps/building-oauth-apps/scopes-for-oauth-apps/#available-scopes">here</a>.
             *
             * @deprecated Please use {@link #setScopes(List)} instead.
             */
            @Deprecated
            @NonNull
            public GitHubBuilder setPermissions(@NonNull List<String> permissions) {
                setScopes(permissions);
                return this;
            }
        }

        /**
         * {@link IdpConfig} builder for the Apple provider.
         */
        public static final class AppleBuilder extends GenericOAuthProviderBuilder {
            private static final String PROVIDER_NAME = "Apple";

            public AppleBuilder() {
                super(APPLE_PROVIDER, PROVIDER_NAME, R.layout.fui_idp_button_apple);
            }
        }

        /**
         * {@link IdpConfig} builder for the Microsoft provider.
         */
        public static final class MicrosoftBuilder extends GenericOAuthProviderBuilder {
            private static final String PROVIDER_NAME = "Microsoft";

            public MicrosoftBuilder() {
                super(MICROSOFT_PROVIDER, PROVIDER_NAME, R.layout.fui_idp_button_microsoft);
            }
        }

        /**
         * {@link IdpConfig} builder for the Yahoo provider.
         */
        public static final class YahooBuilder extends GenericOAuthProviderBuilder {
            private static final String PROVIDER_NAME = "Yahoo";

            public YahooBuilder() {
                super(YAHOO_PROVIDER, PROVIDER_NAME, R.layout.fui_idp_button_yahoo);
            }
        }

        /**
         * {@link IdpConfig} builder for a Generic OAuth provider.
         */
        public static class GenericOAuthProviderBuilder extends Builder {

            public GenericOAuthProviderBuilder(@NonNull String providerId,
                                               @NonNull String providerName,
                                               int buttonId) {
                super(providerId);

                Preconditions.checkNotNull(providerId, "The provider ID cannot be null.");
                Preconditions.checkNotNull(providerName, "The provider name cannot be null.");

                getParams().putString(
                        ExtraConstants.GENERIC_OAUTH_PROVIDER_ID, providerId);
                getParams().putString(
                        ExtraConstants.GENERIC_OAUTH_PROVIDER_NAME, providerName);
                getParams().putInt(
                        ExtraConstants.GENERIC_OAUTH_BUTTON_ID, buttonId);

            }

            @NonNull
            public GenericOAuthProviderBuilder setScopes(@NonNull List<String> scopes) {
                getParams().putStringArrayList(
                        ExtraConstants.GENERIC_OAUTH_SCOPES, new ArrayList<>(scopes));
                return this;
            }

            @NonNull
            public GenericOAuthProviderBuilder setCustomParameters(
                    @NonNull Map<String, String> customParameters) {
                getParams().putSerializable(
                        ExtraConstants.GENERIC_OAUTH_CUSTOM_PARAMETERS,
                        new HashMap<>(customParameters));
                return this;
            }
        }
    }

    /**
     * Base builder for both {@link SignInIntentBuilder}.
     */
    @SuppressWarnings(value = "unchecked")
    private abstract class AuthIntentBuilder<T extends AuthIntentBuilder> {
        final List<IdpConfig> mProviders = new ArrayList<>();
        IdpConfig mDefaultProvider = null;
        int mLogo = NO_LOGO;
        int mTheme = getDefaultTheme();
        String mTosUrl;
        String mPrivacyPolicyUrl;
        boolean mAlwaysShowProviderChoice = false;
        boolean mLockOrientation = false;
        boolean mEnableCredentials = true;
        AuthMethodPickerLayout mAuthMethodPickerLayout = null;
        ActionCodeSettings mPasswordSettings = null;

        /**
         * Specifies the theme to use for the application flow. If no theme is specified, a
         * default theme will be used.
         */
        @NonNull
        public T setTheme(@StyleRes int theme) {
            mTheme = Preconditions.checkValidStyle(
                    mApp.getApplicationContext(),
                    theme,
                    "theme identifier is unknown or not a style definition");
            return (T) this;
        }

        /**
         * Specifies the logo to use for the {@link AuthMethodPickerActivity}. If no logo is
         * specified, none will be used.
         */
        @NonNull
        public T setLogo(@DrawableRes int logo) {
            mLogo = logo;
            return (T) this;
        }

        /**
         * Specifies the terms-of-service URL for the application.
         *
         * @deprecated Please use {@link #setTosAndPrivacyPolicyUrls(String, String)} For the Tos
         * link to be displayed a Privacy Policy url must also be provided.
         */
        @NonNull
        @Deprecated
        public T setTosUrl(@Nullable String tosUrl) {
            mTosUrl = tosUrl;
            return (T) this;
        }

        /**
         * Specifies the privacy policy URL for the application.
         *
         * @deprecated Please use {@link #setTosAndPrivacyPolicyUrls(String, String)} For the
         * Privacy Policy link to be displayed a Tos url must also be provided.
         */
        @NonNull
        @Deprecated
        public T setPrivacyPolicyUrl(@Nullable String privacyPolicyUrl) {
            mPrivacyPolicyUrl = privacyPolicyUrl;
            return (T) this;
        }

        @NonNull
        public T setTosAndPrivacyPolicyUrls(@NonNull String tosUrl,
                                            @NonNull String privacyPolicyUrl) {
            Preconditions.checkNotNull(tosUrl, "tosUrl cannot be null");
            Preconditions.checkNotNull(privacyPolicyUrl, "privacyPolicyUrl cannot be null");
            mTosUrl = tosUrl;
            mPrivacyPolicyUrl = privacyPolicyUrl;
            return (T) this;
        }

        /**
         * Specifies the set of supported authentication providers. At least one provider must
         * be specified. There may only be one instance of each provider. Anonymous provider cannot
         * be the only provider specified.
         * <p>
         * <p>If no providers are explicitly specified by calling this method, then the email
         * provider is the default supported provider.
         *
         * @param idpConfigs a list of {@link IdpConfig}s, where each {@link IdpConfig} contains the
         *                   configuration parameters for the IDP.
         * @throws IllegalStateException if anonymous provider is the only specified provider.
         * @see IdpConfig
         */
        @NonNull
        public T setAvailableProviders(@NonNull List<IdpConfig> idpConfigs) {
            Preconditions.checkNotNull(idpConfigs, "idpConfigs cannot be null");
            if (idpConfigs.size() == 1 &&
                    idpConfigs.get(0).getProviderId().equals(ANONYMOUS_PROVIDER)) {
                throw new IllegalStateException("Sign in as guest cannot be the only sign in " +
                        "method. In this case, sign the user in anonymously your self; " +
                        "no UI is needed.");
            }

            mProviders.clear();

            for (IdpConfig config : idpConfigs) {
                if (mProviders.contains(config)) {
                    throw new IllegalArgumentException("Each provider can only be set once. "
                            + config.getProviderId()
                            + " was set twice.");
                } else {
                    mProviders.add(config);
                }
            }

            return (T) this;
        }

        /**
         * Specifies the default authentication provider, bypassing the provider selection screen.
         * The provider here must already be included via {@link #setAvailableProviders(List)}, and
         * this method is incompatible with {@link #setAlwaysShowSignInMethodScreen(boolean)}.
         *
         * @param config the default {@link IdpConfig} to use.
         */
        @NonNull
        public T setDefaultProvider(@Nullable IdpConfig config) {
            if (config != null) {
                if (!mProviders.contains(config)) {
                    throw new IllegalStateException(
                            "Default provider not in available providers list.");
                }
                if (mAlwaysShowProviderChoice) {
                    throw new IllegalStateException(
                            "Can't set default provider and always show provider choice.");
                }
            }
            mDefaultProvider = config;
            return (T) this;
        }

        /**
         * Enables or disables the use of Credential Manager for Passwords credential selector
         * <p>
         * <p>Is enabled by default.
         *
         * @param enableCredentials enables credential selector before signup
         */
        @NonNull
        public T setCredentialManagerEnabled(boolean enableCredentials) {
            mEnableCredentials = enableCredentials;
            return (T) this;
        }

        /**
         * Set a custom layout for the AuthMethodPickerActivity screen.
         * See {@link AuthMethodPickerLayout}.
         *
         * @param authMethodPickerLayout custom layout descriptor object.
         */
        @NonNull
        public T setAuthMethodPickerLayout(@NonNull AuthMethodPickerLayout authMethodPickerLayout) {
            mAuthMethodPickerLayout = authMethodPickerLayout;
            return (T) this;
        }

        /**
         * Forces the sign-in method choice screen to always show, even if there is only
         * a single provider configured.
         * <p>
         * <p>This is false by default.
         *
         * @param alwaysShow if true, force the sign-in choice screen to show.
         */
        @NonNull
        public T setAlwaysShowSignInMethodScreen(boolean alwaysShow) {
            if (alwaysShow && mDefaultProvider != null) {
                throw new IllegalStateException(
                        "Can't show provider choice with a default provider.");
            }
            mAlwaysShowProviderChoice = alwaysShow;
            return (T) this;
        }

        /**
         * Enable or disables the orientation for small devices to be locked in
         * Portrait orientation
         * <p>
         * <p>This is false by default.
         *
         * @param lockOrientation if true, force the activities to be in Portrait orientation.
         */
        @NonNull
        public T setLockOrientation(boolean lockOrientation) {
            mLockOrientation = lockOrientation;
            return (T) this;
        }

        /**
         * Set custom settings for the RecoverPasswordActivity.
         *
         * @param passwordSettings to allow additional state via a continue URL.
         */
        @NonNull
        public T setResetPasswordSettings(ActionCodeSettings passwordSettings) {
            mPasswordSettings = passwordSettings;
            return (T) this;
        }

        @CallSuper
        @NonNull
        public Intent build() {
            if (mProviders.isEmpty()) {
                mProviders.add(new IdpConfig.EmailBuilder().build());
            }

            return KickoffActivity.createIntent(mApp.getApplicationContext(), getFlowParams());
        }

        protected abstract FlowParameters getFlowParams();
    }

    /**
     * Builder for the intent to start the user authentication flow.
     */
    public final class SignInIntentBuilder extends AuthIntentBuilder<SignInIntentBuilder> {

        private String mEmailLink;
        private boolean mEnableAnonymousUpgrade;

        private SignInIntentBuilder() {
            super();
        }

        /**
         * Specifies the email link to be used for sign in. When set, a sign in attempt will be
         * made immediately.
         */
        @NonNull
        public SignInIntentBuilder setEmailLink(@NonNull final String emailLink) {
            mEmailLink = emailLink;
            return this;
        }

        /**
         * Enables upgrading anonymous accounts to full accounts during the sign-in flow.
         * This is disabled by default.
         *
         * @throws IllegalStateException when you attempt to enable anonymous user upgrade
         *                               without forcing the same device flow for email link sign in.
         */
        @NonNull
        public SignInIntentBuilder enableAnonymousUsersAutoUpgrade() {
            mEnableAnonymousUpgrade = true;
            validateEmailBuilderConfig();
            return this;
        }

        private void validateEmailBuilderConfig() {
            for (int i = 0; i < mProviders.size(); i++) {
                IdpConfig config = mProviders.get(i);
                if (config.getProviderId().equals(EMAIL_LINK_PROVIDER)) {
                    boolean emailLinkForceSameDevice =
                            config.getParams().getBoolean(ExtraConstants.FORCE_SAME_DEVICE, true);
                    if (!emailLinkForceSameDevice) {
                        throw new IllegalStateException("You must force the same device flow " +
                                "when using email link sign in with anonymous user upgrade");
                    }
                }
            }
        }

        @Override
        protected FlowParameters getFlowParams() {
            return new FlowParameters(
                    mApp.getName(),
                    mProviders,
                    mDefaultProvider,
                    mTheme,
                    mLogo,
                    mTosUrl,
                    mPrivacyPolicyUrl,
                    mEnableCredentials,
                    mEnableAnonymousUpgrade,
                    mAlwaysShowProviderChoice,
                    mLockOrientation,
                    mEmailLink,
                    mPasswordSettings,
                    mAuthMethodPickerLayout);
        }
    }
}
