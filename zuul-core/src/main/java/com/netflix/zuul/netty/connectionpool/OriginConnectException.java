/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.connectionpool;


import com.netflix.zuul.exception.ErrorType;

/**
 * Wrapper for exceptions failing to connect to origin with details on which server failed the attempt.失败连接到原始服务器的异常包装器，详细说明哪个服务器失败。
 */
public class OriginConnectException extends Exception {

    private final ErrorType errorType;

    public OriginConnectException(String message, ErrorType errorType) {
        // ensure this exception does not fill its stacktrace, this causes a 10x slowdown确保此异常不填充其堆栈跟踪，这将导致10倍的减速
        super(message, null, true, false);
        this.errorType = errorType;
    }

    public OriginConnectException(String message, Throwable cause, ErrorType errorType) {
        // ensure this exception does not fill its stacktrace, this causes a 10x slowdown确保此异常不填充其堆栈跟踪，这将导致10倍的减速
        super(message, cause, true, false);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

}
