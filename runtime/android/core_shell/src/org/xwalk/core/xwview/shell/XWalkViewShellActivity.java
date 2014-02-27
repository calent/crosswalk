// Copyright (c) 2013 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.core.xwview.shell;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.drawable.ClipDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import org.chromium.base.BaseSwitches;
import org.chromium.base.CommandLine;
import org.chromium.content.app.LibraryLoader;
import org.chromium.content.browser.TracingControllerAndroid;
import org.xwalk.core.client.XWalkDefaultWebChromeClient;
import org.xwalk.core.XWalkView;

public class XWalkViewShellActivity extends Activity {
    public static final String COMMAND_LINE_FILE = "/data/local/tmp/xwview-shell-command-line";
    private static final String TAG = XWalkViewShellActivity.class.getName();
    public static final String COMMAND_LINE_ARGS_KEY = "commandLineArgs";
    private static final long COMPLETED_PROGRESS_TIMEOUT_MS = 200;
    private static final String ACTION_LAUNCH_URL = "org.xwalk.core.xwview.shell.launch";

    private LinearLayout mToolbar;
    private EditText mUrlTextView;
    private ImageButton mPrevButton;
    private ImageButton mNextButton;
    private ImageButton mStopButton;
    private ImageButton mReloadButton;
    private ClipDrawable mProgressDrawable;
    private XWalkView mView;
    private TracingControllerAndroid mTracingController;
    private BroadcastReceiver mReceiver;

    private Runnable mClearProgressRunnable = new Runnable() {
        @Override
        public void run() {
            mProgressDrawable.setLevel(0);
        }
    };

    TracingControllerAndroid getTracingController() {
        if (mTracingController == null) {
            mTracingController = new TracingControllerAndroid(this);
        }
        return mTracingController;
    }

    private void registerTracingReceiverWhenIdle() {
        // Delay tracing receiver registration until the main loop is idle.
        Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                // Will retry if the native library is not initialized yet.
                if (!LibraryLoader.isInitialized()) return true;
                try {
                    getTracingController().registerReceiver(XWalkViewShellActivity.this);
                } catch (SecurityException e) {
                    Log.w(TAG, "failed to register tracing receiver: " + e.getMessage());
                }
                return false;
            }
        });
    }

    private void unregisterTracingReceiver() {
        try {
            getTracingController().unregisterReceiver(this);
        } catch (SecurityException e) {
            Log.w(TAG, "failed to unregister tracing receiver: " + e.getMessage());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        registerTracingReceiverWhenIdle();

        if (!CommandLine.isInitialized()) {
            CommandLine.initFromFile(COMMAND_LINE_FILE);
            String[] commandLineParams = getCommandLineParamsFromIntent(getIntent());
            if (commandLineParams != null) {
                CommandLine.getInstance().appendSwitchesAndArguments(commandLineParams);
            }
        }

        waitForDebuggerIfNeeded();

        setContentView(R.layout.testshell_activity);

        mView = (XWalkView) findViewById(R.id.content_container);
        mToolbar = (LinearLayout) findViewById(R.id.toolbar);
        mProgressDrawable = (ClipDrawable) findViewById(R.id.toolbar).getBackground();

        initializeUrlField();
        initializeButtons();
        initializeXWalkViewClients();

        IntentFilter intentFilter = new IntentFilter(ACTION_LAUNCH_URL);
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle == null)
                    return;

                if (bundle.containsKey("url")) {
                    String extra = bundle.getString("url");
                    mView.loadUrl(sanitizeUrl(extra));
                }
            }
        };
        registerReceiver(mReceiver, intentFilter);
        mView.enableRemoteDebugging();
    }

    @Override
    public void onPause() {
        super.onPause();
        mView.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        mView.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        unregisterTracingReceiver();
        mView.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mView.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mView.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (!mView.onNewIntent(intent)) super.onNewIntent(intent);
    }

    private void waitForDebuggerIfNeeded() {
        if (CommandLine.getInstance().hasSwitch(BaseSwitches.WAIT_FOR_JAVA_DEBUGGER)) {
            Log.e(TAG, "Waiting for Java debugger to connect...");
            android.os.Debug.waitForDebugger();
            Log.e(TAG, "Java debugger connected. Resuming execution.");
        }
    }

    private static String[] getCommandLineParamsFromIntent(Intent intent) {
        return intent != null ? intent.getStringArrayExtra(COMMAND_LINE_ARGS_KEY) : null;
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return url;
        if (url.startsWith("www.") || url.indexOf(":") == -1) url = "http://" + url;
        return url;
    }

    private void initializeUrlField() {
        mUrlTextView = (EditText) findViewById(R.id.url);
        mUrlTextView.setOnEditorActionListener(new OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((actionId != EditorInfo.IME_ACTION_GO) && (event == null ||
                        event.getKeyCode() != KeyEvent.KEYCODE_ENTER ||
                        event.getAction() != KeyEvent.ACTION_DOWN)) {
                    return false;
                }

                mView.loadUrl(sanitizeUrl(mUrlTextView.getText().toString()));
                mUrlTextView.clearFocus();
                setKeyboardVisibilityForUrl(false);
                return true;
            }
        });
        mUrlTextView.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                setKeyboardVisibilityForUrl(hasFocus);
                mNextButton.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
                mPrevButton.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
                mStopButton.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
                mReloadButton.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
                if (!hasFocus) {
                    mUrlTextView.setText(mView.getUrl());
                }
            }
        });

    }

    private void initializeButtons() {
        mPrevButton = (ImageButton) findViewById(R.id.prev);
        mPrevButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mView.canGoBack()) mView.goBack();
            }
        });

        mNextButton = (ImageButton) findViewById(R.id.next);
        mNextButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mView.canGoForward()) mView.goForward();
            }
        });

        mStopButton = (ImageButton) findViewById(R.id.stop);
        mStopButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mView.stopLoading();
            }
        });

        mReloadButton = (ImageButton) findViewById(R.id.reload);
        mReloadButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mView.reload();
            }
        });
    }

    private void initializeXWalkViewClients() {
        mView.setXWalkWebChromeClient(new XWalkDefaultWebChromeClient(this, mView) {
            public void onProgressChanged(XWalkView view, int newProgress) {
                mToolbar.removeCallbacks(mClearProgressRunnable);

                mProgressDrawable.setLevel((int) (100.0 * newProgress));
                if (newProgress == 100)
                    mToolbar.postDelayed(mClearProgressRunnable, COMPLETED_PROGRESS_TIMEOUT_MS);
                    mUrlTextView.setText(mView.getUrl());
            }
        });
    }
    private void setKeyboardVisibilityForUrl(boolean visible) {
        InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (visible) {
            imm.showSoftInput(mUrlTextView, InputMethodManager.SHOW_IMPLICIT);
        } else {
            imm.hideSoftInputFromWindow(mUrlTextView.getWindowToken(), 0);
        }
    }
}
