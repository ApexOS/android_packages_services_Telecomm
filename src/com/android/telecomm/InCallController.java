/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.telecomm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;
import android.telecomm.CallState;

import com.android.internal.telecomm.IInCallService;
import com.google.common.collect.ImmutableCollection;

/**
 * Binds to {@link IInCallService} and provides the service to {@link CallsManager} through which it
 * can send updates to the in-call app. This class is created and owned by CallsManager and retains
 * a binding to the {@link IInCallService} (implemented by the in-call app).
 */
public final class InCallController extends CallsManagerListenerBase {
    /**
     * Used to bind to the in-call app and triggers the start of communication between
     * this class and in-call app.
     */
    private class InCallServiceConnection implements ServiceConnection {
        /** {@inheritDoc} */
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            onConnected(service);
        }

        /** {@inheritDoc} */
        @Override public void onServiceDisconnected(ComponentName name) {
            onDisconnected();
        }
    }

    /**
     * Package name of the in-call app. Although in-call code in kept in its own namespace, it is
     * ultimately compiled into the dialer APK, hence the difference in namespaces between this and
     * {@link #IN_CALL_SERVICE_CLASS_NAME}.
     * TODO(santoscordon): Change this into config.xml resource entry.
     */
    private static final String IN_CALL_PACKAGE_NAME = "com.google.android.dialer";

    /**
     * Class name of the component within in-call app which implements {@link IInCallService}.
     */
    private static final String IN_CALL_SERVICE_CLASS_NAME =
            "com.android.incallui.InCallServiceImpl";

    /** Maintains a binding connection to the in-call app. */
    private final InCallServiceConnection mConnection = new InCallServiceConnection();

    /** The in-call app implementation, see {@link IInCallService}. */
    private IInCallService mInCallService;

    private final CallIdMapper mCallIdMapper = new CallIdMapper("InCall");

    IInCallService getService() {
        return mInCallService;
    }

    @Override
    public void onCallAdded(Call call) {
        if (mInCallService == null) {
            bind();
        } else {
            Log.i(this, "Adding call: %s", call);
            mCallIdMapper.addCall(call);
            CallInfo callInfo = call.toCallInfo(mCallIdMapper.getCallId(call));
            try {
                mInCallService.addCall(callInfo);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (CallsManager.getInstance().getCalls().isEmpty()) {
            // TODO(sail): Wait for all messages to be delivered to the service before unbinding.
            unbind();
        }
        mCallIdMapper.removeCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (mInCallService == null) {
            return;
        }

        String callId = mCallIdMapper.getCallId(call);
        switch (newState) {
            case ACTIVE:
                Log.i(this, "Mark call as ACTIVE: %s", callId);
                try {
                    mInCallService.setActive(callId);
                } catch (RemoteException e) {
                }
                break;
            case ON_HOLD:
                Log.i(this, "Mark call as HOLD: %s", callId);
                try {
                    mInCallService.setOnHold(callId);
                } catch (RemoteException e) {
                }
                break;
            case DISCONNECTED:
                Log.i(this, "Mark call as DISCONNECTED: %s", callId);
                try {
                    mInCallService.setDisconnected(callId, call.getDisconnectCause());
                } catch (RemoteException e) {
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onCallHandoffHandleChanged(Call call, Uri oldHandle, Uri newHandle) {
        if (mInCallService != null) {
            try {
                mInCallService.setHandoffEnabled(mCallIdMapper.getCallId(call), newHandle != null);
            } catch (RemoteException e) {
                Log.e(this, e, "Exception attempting to call setHandoffEnabled.");
            }
        }
    }

    @Override
    public void onAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState) {
        if (mInCallService != null) {
            Log.i(this, "Calling onAudioStateChanged, audioState: %s -> %s", oldAudioState,
                    newAudioState);
            try {
                mInCallService.onAudioStateChanged(newAudioState);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     */
    private void unbind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService != null) {
            Log.i(this, "Unbinding from InCallService");
            TelecommApp.getInstance().unbindService(mConnection);
            mInCallService = null;
        }
    }

    /**
     * Binds to the in-call app if not already connected by binding directly to the saved
     * component name of the {@link IInCallService} implementation.
     */
    private void bind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService == null) {
            ComponentName component =
                    new ComponentName(IN_CALL_PACKAGE_NAME, IN_CALL_SERVICE_CLASS_NAME);
            Log.i(this, "Attempting to bind to InCallService: %s", component);

            Intent serviceIntent = new Intent(IInCallService.class.getName());
            serviceIntent.setComponent(component);

            Context context = TelecommApp.getInstance();
            if (!context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.w(this, "Could not connect to the in-call app (%s)", component);

                // TODO(santoscordon): Implement retry or fall-back-to-default logic.
            }
        }
    }

    /**
     * Persists the {@link IInCallService} instance and starts the communication between
     * this class and in-call app by sending the first update to in-call app. This method is
     * called after a successful binding connection is established.
     *
     * @param service The {@link IInCallService} implementation.
     */
    private void onConnected(IBinder service) {
        ThreadUtil.checkOnMainThread();
        mInCallService = IInCallService.Stub.asInterface(service);

        try {
            mInCallService.setInCallAdapter(new InCallAdapter(CallsManager.getInstance(),
                    mCallIdMapper));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the in-call adapter.");
            mInCallService = null;
            return;
        }

        // Upon successful connection, send the state of the world to the in-call app.
        ImmutableCollection<Call> calls = CallsManager.getInstance().getCalls();
        if (!calls.isEmpty()) {
            for (Call call : calls) {
                onCallAdded(call);
                onCallHandoffHandleChanged(call, null, call.getHandoffHandle());
            }
            onAudioStateChanged(null, CallsManager.getInstance().getAudioState());
        } else {
            unbind();
        }
    }

    /**
     * Cleans up the instance of in-call app after the service has been unbound.
     */
    private void onDisconnected() {
        ThreadUtil.checkOnMainThread();
        mInCallService = null;
    }
}
