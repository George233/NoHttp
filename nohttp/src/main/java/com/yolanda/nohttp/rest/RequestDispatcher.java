/*
 * Copyright 2015 Yan Zhenjie
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
package com.yolanda.nohttp.rest;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.yolanda.nohttp.Headers;
import com.yolanda.nohttp.Logger;

import java.util.concurrent.BlockingQueue;

/**
 * <p>
 * Request queue polling thread.
 * </p>
 * Created in Oct 19, 2015 8:35:35 AM.
 *
 * @author Yan Zhenjie.
 */
public class RequestDispatcher extends Thread {
    /**
     * Gets the lock for Handler to prevent the request result from confusing.
     */
    private static final Object HANDLER_LOCK = new Object();
    /**
     * Poster of send request result.
     */
    private static Handler sRequestHandler;
    /**
     * Request queue.
     */
    private final BlockingQueue<Request<?>> mRequestQueue;
    /**
     * Un finish task queue.
     */
    private final BlockingQueue<Request<?>> mUnFinishQueue;
    /**
     * HTTP request parse interface.
     */
    private final ImplRestParser mImplRestParser;
    /**
     * Whether the current request queue polling thread is out of.
     */
    private volatile boolean mQuit = false;

    /**
     * Create a request queue polling thread.
     *
     * @param unFinishQueue  un finish queue.
     * @param requestQueue   request queue.
     * @param implRestParser network request task actuator.
     */
    public RequestDispatcher(BlockingQueue<Request<?>> unFinishQueue, BlockingQueue<Request<?>> requestQueue, ImplRestParser implRestParser) {
        mUnFinishQueue = unFinishQueue;
        mRequestQueue = requestQueue;
        mImplRestParser = implRestParser;
    }

    /**
     * Exit polling thread.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (!mQuit) {
            final Request<?> request;
            try {
                request = mRequestQueue.take();
            } catch (InterruptedException e) {
                if (!mQuit)
                    return;
                continue;
            }

            if (request.isCanceled()) {
                Logger.d(request.url() + " is canceled.");
                continue;
            }

            final int what = request.what();
            final OnResponseListener<?> responseListener = request.responseListener();

            request.start();
            // start
            final ThreadPoster startThread = new ThreadPoster(what, responseListener);
            startThread.onStart();
            getPosterHandler().post(startThread);

            // request
            Response<?> response = mImplRestParser.parserRequest(request);

            // remove it from queue
            mUnFinishQueue.remove(request);

            // finish
            final ThreadPoster finishThread = new ThreadPoster(what, responseListener);
            finishThread.onFinished();
            getPosterHandler().post(finishThread);
            request.finish();

            // response
            if (request.isCanceled())
                Logger.d(request.url() + " finish, but it's canceled.");
            else {
                final ThreadPoster responseThread = new ThreadPoster(what, responseListener);
                responseThread.onResponse(response);
                getPosterHandler().post(responseThread);
            }
        }
    }

    private Handler getPosterHandler() {
        synchronized (HANDLER_LOCK) {
            if (sRequestHandler == null)
                sRequestHandler = new Handler(Looper.getMainLooper());
        }
        return sRequestHandler;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private class ThreadPoster implements Runnable {

        public static final int COMMAND_START = 0;
        public static final int COMMAND_RESPONSE = 1;
        public static final int COMMAND_FINISH = 2;

        private final int what;
        private final OnResponseListener responseListener;

        private int command;
        private Response response;

        public ThreadPoster(int what, OnResponseListener<?> responseListener) {
            this.what = what;
            this.responseListener = responseListener;
        }

        public void onStart() {
            this.command = COMMAND_START;
        }

        public void onResponse(Response response) {
            this.command = COMMAND_RESPONSE;
            this.response = response;
        }

        public void onFinished() {
            this.command = COMMAND_FINISH;
        }

        @Override
        public void run() {
            if (responseListener != null) {
                if (command == COMMAND_START)
                    responseListener.onStart(what);
                else if (command == COMMAND_FINISH)
                    responseListener.onFinish(what);
                else if (command == COMMAND_RESPONSE) {
                    if (response == null) {
                        responseListener.onFailed(what, null, null, new Exception("Unknown abnormal."), 0, 0);
                    } else {
                        if (response.isSucceed()) {
                            responseListener.onSucceed(what, response);
                        } else {
                            Headers headers = response.getHeaders();
                            responseListener.onFailed(what, response.url(), response.getTag(), response.getException(), headers == null ? -1 : headers.getResponseCode(), response.getNetworkMillis());
                        }
                    }
                }
            }
        }
    }
}
