/*
 * Copyright (C) 2017 Vincent Breitmoser <look@my.amazin.horse>
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

package org.sufficientlysecure.keychain.remote.ui;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.openintents.openpgp.util.OpenPgpUtils.UserId;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.remote.ui.RequestKeyPermissionPresenter.RequestKeyPermissionMvpView;
import org.sufficientlysecure.keychain.ui.dialog.CustomAlertDialogBuilder;
import org.sufficientlysecure.keychain.ui.util.ThemeChanger;


public class RequestKeyPermissionActivity extends FragmentActivity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_REQUESTED_KEY_ID = "requested_key_id";


    private RequestKeyPermissionPresenter presenter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        presenter = RequestKeyPermissionPresenter.createRequestKeyPermissionPresenter(getBaseContext());

        if (savedInstanceState == null) {
            RequestKeyPermissionFragment frag = new RequestKeyPermissionFragment();
            frag.show(getSupportFragmentManager(), "requestKeyDialog");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = getIntent();
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        long masterKeyId = intent.getLongExtra(EXTRA_REQUESTED_KEY_ID, Constants.key.none);

        presenter.setupFromIntentData(packageName, masterKeyId);
    }

    public static class RequestKeyPermissionFragment extends DialogFragment {
        private RequestKeyPermissionMvpView mvpView;
        private RequestKeyPermissionPresenter presenter;
        private Button buttonCancel;
        private Button buttonAllow;

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();

            ContextThemeWrapper theme = ThemeChanger.getDialogThemeWrapper(activity);
            CustomAlertDialogBuilder alert = new CustomAlertDialogBuilder(theme);

            @SuppressLint("InflateParams")
            View view = LayoutInflater.from(theme).inflate(R.layout.api_remote_request_key_permission, null, false);
            alert.setView(view);

            buttonAllow = (Button) view.findViewById(R.id.button_allow);
            buttonCancel = (Button) view.findViewById(R.id.button_cancel);

            setupListenersForPresenter();
            mvpView = createMvpView(view);

            return alert.create();
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            presenter = ((RequestKeyPermissionActivity) getActivity()).presenter;
            presenter.setView(mvpView);
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);

            if (presenter != null) {
                presenter.onCancel();
            }
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);

            if (presenter != null) {
                presenter.setView(null);
                presenter = null;
            }
        }

        @NonNull
        private RequestKeyPermissionMvpView createMvpView(View view) {
            final TextView titleText = (TextView) view.findViewById(R.id.select_identity_key_title);
            final TextView keyUserIdView = (TextView) view.findViewById(R.id.select_key_item_name);
            final ImageView iconClientApp = (ImageView) view.findViewById(R.id.icon_client_app);
            final View keyUnavailableWarning = view.findViewById(R.id.requested_key_unavailable_warning);
            final View keyInfoLayout = view.findViewById(R.id.key_info_layout);

            return new RequestKeyPermissionMvpView() {
                @Override
                public void switchToLayoutRequestKeyChoice() {
                    keyInfoLayout.setVisibility(View.VISIBLE);
                    keyUnavailableWarning.setVisibility(View.GONE);
                    buttonAllow.setEnabled(true);
                }

                @Override
                public void switchToLayoutNoSecret() {
                    keyInfoLayout.setVisibility(View.VISIBLE);
                    keyUnavailableWarning.setVisibility(View.VISIBLE);
                    buttonAllow.setEnabled(false);
                }

                @Override
                public void displayKeyInfo(UserId userId) {
                    keyUserIdView.setText(userId.name);
                }

                @Override
                public void finish() {
                    FragmentActivity activity = getActivity();
                    if (activity == null) {
                        return;
                    }

                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }

                @Override
                public void finishAsCancelled() {
                    FragmentActivity activity = getActivity();
                    if (activity == null) {
                        return;
                    }

                    activity.setResult(Activity.RESULT_CANCELED);
                    activity.finish();
                }

                @Override
                public void setTitleText(String text) {
                    titleText.setText(text);
                }

                @Override
                public void setTitleClientIcon(Drawable drawable) {
                    iconClientApp.setImageDrawable(drawable);
                }
            };
        }

        private void setupListenersForPresenter() {
            buttonAllow.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    presenter.onClickAllow();
                }
            });

            buttonCancel.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    presenter.onClickCancel();
                }
            });
        }
    }
}
