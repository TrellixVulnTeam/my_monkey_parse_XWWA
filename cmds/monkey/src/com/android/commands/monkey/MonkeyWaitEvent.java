/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.commands.monkey;

import android.app.IActivityManager;
import android.view.IWindowManager;


/**
 * monkey throttle event
 * 间隔事件，用于控制线程执行
 */
public class MonkeyWaitEvent extends MonkeyEvent {
    private long mWaitTime;

    public MonkeyWaitEvent(long waitTime) {
        super(MonkeyEvent.EVENT_TYPE_THROTTLE);
        mWaitTime = waitTime;
    }

    @Override
    public int injectEvent(IWindowManager iwm, IActivityManager iam, int verbose) {
        if (verbose > 1) {
            Logger.out.println("Wait Event for " + mWaitTime + " milliseconds"); //向标准输出流中输出日志
        }
        try {
            Thread.sleep(mWaitTime); //线程休眠指定的时间
        } catch (InterruptedException e1) { //如果线程休眠过程中被中断
            Logger.out.println("** Monkey interrupted in sleep.");
            return MonkeyEvent.INJECT_FAIL; //返回注入事件失败
        }

        return MonkeyEvent.INJECT_SUCCESS; //返回注入事件成功
    }
}
