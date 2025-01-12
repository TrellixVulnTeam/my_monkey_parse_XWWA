/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.ComponentName;
import android.graphics.PointF;
import android.hardware.display.DisplayManagerGlobal;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.util.List;
import java.util.Random;

/**
 * monkey event queue
 * MonkeySourceRandom对象非常重要，默认会走这里
 */
public class MonkeySourceRandom implements MonkeyEventSource {
    /** Key events that move around the UI. */
    /** 创建数组对象，存储4个元素，每个元素为导航按键的KeyCode值*/
    private static final int[] NAV_KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    };
    /**
     * Key events that perform major navigation options (so shouldn't be sent
     * as much).
     * 创建数组对象，存储2个元素，两个KeyCode值
     */
    private static final int[] MAJOR_NAV_KEYS = {
        KeyEvent.KEYCODE_MENU, /*KeyEvent.KEYCODE_SOFT_RIGHT,*/
        KeyEvent.KEYCODE_DPAD_CENTER,
    };
    /** Key events that perform system operations.
     * 创建数组对象，持有8个元素，表示哪些实体按键的KeyCode值
     */

    private static final int[] SYS_KEYS = {
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE,
    };
    /** If a physical key exists?
     *  检查图形按键?，好大的数组，每个元素是boolean值
     */
    private static final boolean[] PHYSICAL_KEY_EXISTS = new boolean[KeyEvent.getMaxKeyCode() + 1];

    /**
     * 静态代码块用于初始化PHYSICAL_KEY_EXISTS数组的元素
     */
    static {
        for (int i = 0; i < PHYSICAL_KEY_EXISTS.length; ++i) {
            PHYSICAL_KEY_EXISTS[i] = true; //将数组的所有默认值，设置为true
        }
        // Only examine SYS_KEYS
        for (int i = 0; i < SYS_KEYS.length; ++i) {
            PHYSICAL_KEY_EXISTS[SYS_KEYS[i]] = KeyCharacterMap.deviceHasKey(SYS_KEYS[i]);
        }
    }

    /** Possible screen rotation degrees
     * 屏幕的4个角度，由一个数组对象存储
     */
    private static final int[] SCREEN_ROTATION_DEGREES = {
      Surface.ROTATION_0,
      Surface.ROTATION_90,
      Surface.ROTATION_180,
      Surface.ROTATION_270,
    };

    /**
     * 公开常量（MonkeySourceRandom下随机生成的事件，各自的事件比例存储在一个数组中，将下标保留在这里）
     * 某个事件的事件比例在数组中的下标值
     * 最后一个FACTORZ_COUNT表示可以调节的事件比例数量
     */
    public static final int FACTOR_TOUCH        = 0; //触摸事件比例的下标
    public static final int FACTOR_MOTION       = 1; //MOTION事件比例的下标
    public static final int FACTOR_PINCHZOOM    = 2; //PINCHZOOM事件比例的下标
    public static final int FACTOR_TRACKBALL    = 3; //TRACKBALL事件比例的下标
    public static final int FACTOR_ROTATION     = 4; //ROTATION事件比例的下标
    public static final int FACTOR_PERMISSION   = 5;
    public static final int FACTOR_NAV          = 6;
    public static final int FACTOR_MAJORNAV     = 7;
    public static final int FACTOR_SYSOPS       = 8;
    public static final int FACTOR_APPSWITCH    = 9;
    public static final int FACTOR_FLIP         = 10;
    public static final int FACTOR_ANYTHING     = 11;    //一共12个事件，需要一个可以存储12个元素的数组
    public static final int FACTORZ_COUNT       = 12;    // should be last+1  使用常量，是因为Monkey持有了一个数组对象，而MonkeySourceRandom中也持有了一个数组对象，它俩的长度一致

    /**
     * 私有常量
     * 表示手势动作
     * 1、点
     * 2、拽
     * 3、多指
     */
    private static final int GESTURE_TAP = 0;
    private static final int GESTURE_DRAG = 1;
    private static final int GESTURE_PINCH_OR_ZOOM = 2;

    /** percentages for each type of event.  These will be remapped to working
     * values after we read any optional values.
     **/
    private float[] mFactors = new float[FACTORZ_COUNT]; //MonkeySourceRandom对象持有一个数组对象，用于保存每个事件的事件比例，不同的事件比例存储在数组不同的下标中
    private List<ComponentName> mMainApps; //MonkeySourceRandom对象持有的List对象，用于保存需要操作的App信息
    private int mEventCount = 0;  //total number of events generated so far //MonkeySourceRandom对象持有的事件数量，但是没有使用
    private MonkeyEventQueue mQ; //MonkeySourceRandom对象持有的双向链表，用于存储每个事件对象（MonkeyEvent对象）
    private Random mRandom; //MonkeySourceRandom对象持有的Random对象
    private int mVerbose = 0; //MonkeySourceRandom对象持有的日志等级
    private long mThrottle = 0; //MonkeySourceRandom对象持有的事件延迟时间，但是没有使用……大牛也会犯错……
    private MonkeyPermissionUtil mPermissionUtil; //MonkeySourceRandom对象持有的MonkeyPermissionUtil对象

    private boolean mKeyboardOpen = false; //持有的键盘是否打开的标志位

    /**
     * 一个工具方法，用于返回keycode值对应的字符串
     * @param keycode keyCode值，int
     * @return keyCode对应的字符串
     */
    public static String getKeyName(int keycode) {
        return KeyEvent.keyCodeToString(keycode);
    }

    /**
     * Looks up the keyCode from a given KEYCODE_NAME.  NOTE: This may
     * be an expensive operation.
     * 一个工具方法，用于通过Keycode字符串，获取keycode值
     *
     * @param keyName the name of the KEYCODE_VALUE to lookup.
     * @returns the intenger keyCode value, or KeyEvent.KEYCODE_UNKNOWN if not found
     */
    public static int getKeyCode(String keyName) {
        return KeyEvent.keyCodeFromString(keyName);
    }

    /**
     * 创建MonkeySourceRandom对象的唯一构造方法，创建一个指定要求的对象，参数需要你来传递
     * @param random Random对象，持有随机种子值
     * @param MainApps List持有的每个元素，每个App的Launch Activity
     * @param throttle 事件的延迟时间
     * @param randomizeThrottle 事件的延迟事件是否需要随机
     * @param permissionTargetSystem 系统权限
     */
    public MonkeySourceRandom(Random random, List<ComponentName> MainApps,
            long throttle, boolean randomizeThrottle, boolean permissionTargetSystem) {
        // default values for random distributions
        // note, these are straight percentages, to match user input (cmd line args)
        // but they will be converted to 0..1 values before the main loop runs.
        mFactors[FACTOR_TOUCH] = 15.0f; //TOUCH事件的默认值
        mFactors[FACTOR_MOTION] = 10.0f; //MOTION事件的默认值
        mFactors[FACTOR_TRACKBALL] = 15.0f; //TRACKBALL事件的默认值
        // Adjust the values if we want to enable rotation by default.
        mFactors[FACTOR_ROTATION] = 0.0f; //ROTATION事件的默认值
        mFactors[FACTOR_NAV] = 25.0f;
        mFactors[FACTOR_MAJORNAV] = 15.0f;
        mFactors[FACTOR_SYSOPS] = 2.0f;
        mFactors[FACTOR_APPSWITCH] = 2.0f;
        mFactors[FACTOR_FLIP] = 1.0f;
        // disbale permission by default
        mFactors[FACTOR_PERMISSION] = 0.0f; //默认值为0.0
        mFactors[FACTOR_ANYTHING] = 13.0f;
        mFactors[FACTOR_PINCHZOOM] = 2.0f;

        mRandom = random;
        mMainApps = MainApps; //将获取到主Activity的List赋值给mMainApps
        mQ = new MonkeyEventQueue(random, throttle, randomizeThrottle);//创建MonkeyEventQueue对象，双向链表，用于存储事件对象
        mPermissionUtil = new MonkeyPermissionUtil(); //创建MonkeyPermissionUtil对象
        mPermissionUtil.setTargetSystemPackages(permissionTargetSystem); //将permissionTargetSystem值设置到MonkeyPermissionUtil对象中
    }

    /**
     * Adjust the percentages (after applying user values) and then normalize to a 0..1 scale.
     *  检查与调整事件的比例值
     *  返回值表示事件比例是否正确，true 表示正确
     */
    private boolean adjustEventFactors() {
        // go through all values and compute totals for user & default values
        float userSum = 0.0f; //临时变量用于记录用户自己设置的事件比例总数
        float defaultSum = 0.0f; //临时变量用于记录事件默认比例总数
        int defaultCount = 0; //临时变量，记录使用的默认事件总数
        for (int i = 0; i < FACTORZ_COUNT; ++i) { //开始遍历每一个存储的事件比例
            if (mFactors[i] <= 0.0f) {   // user values are zero or negative 用户设置的都是小于0的数字（负值）在Monkey中设置
                userSum -= mFactors[i]; //计算出一个用户比例的总值
            } else {
                defaultSum += mFactors[i]; //这是用户没有设置的比例值总数
                ++defaultCount;  //更新默认使用的事件总数
            }
        }

        // if the user request was > 100%, reject it，用户没有设置的全部事件，绝不能超过100%，如果没有设置所有的事件比例，则可以小于100%
        if (userSum > 100.0f) {
            Logger.err.println("** Event weights > 100%"); //标准错误流写入错误信息，告知Event weights超过100%
            return false; //事件比例出错（用户自己设置的事件比例出错）
        }

        // if the user specified all of the weights, then they need to be 100%
        // 如果用户设置了所有的事件比例，事件比例不能小于100%，草，严谨……，必须是100%（只有在所有事件比例全部设置的情况，要求）
        if (defaultCount == 0 && (userSum < 99.9f || userSum > 100.1f)) {
            Logger.err.println("** Event weights != 100%");
            return false;
        }

        // compute the adjustment necessary
        float defaultsTarget = (100.0f - userSum); //100减去用户自己设置的比例总数，还能余下的事件比例值
        float defaultsAdjustment = defaultsTarget / defaultSum;  //计算剩余事件比例值与使用的默认事件总值的比例

        // fix all values, by adjusting defaults, or flipping user values back to >0
        for (int i = 0; i < FACTORZ_COUNT; ++i) { //遍历所有事件比例值
            if (mFactors[i] <= 0.0f) {   // user values are zero or negative
                mFactors[i] = -mFactors[i]; //把用户设置的负值全部修复成整数值
            } else {
                mFactors[i] *= defaultsAdjustment; //把用户没有设置的值全部乘以一个比例值（我那里因为个人设置的事件比例为100%，所以，这会让剩余的默认事件比例值全部为0）
            }
        }

        // if verbose, show factors
        if (mVerbose > 0) { //根据事件等级，向标准输出流输出日志
            Logger.out.println("// Event percentages:");
            for (int i = 0; i < FACTORZ_COUNT; ++i) {
                Logger.out.println("//   " + i + ": " + mFactors[i] + "%");
            }
        }

        /**
         * 无效key值出现时，直接返回事件比例检查出错
         */
        if (!validateKeys()) {
            return false;
        }

        // finally, normalize and convert to running sum
        float sum = 0.0f;  //这里没看懂^^^，比如touch是20，motion是10
        for (int i = 0; i < FACTORZ_COUNT; ++i) {
            sum += mFactors[i] / 100.0f; //调整为百分比值
            mFactors[i] = sum; //这里赋值时，touch为0.2，motion则是0.3……我也是醉了，这个事件比例是不是bug？不是bug，刻意如此
        }
        return true;
    }

    /**
     *
     * @param catName
     * @param keys
     * @param factor
     * @return true表示有效，如果比例值小于0.1有效，如果KEY_CODE是正确的在有效范围内的KEY_CODE值，也会返回true
     */
    private static boolean validateKeyCategory(String catName, int[] keys, float factor) {
        if (factor < 0.1f) {
            return true;
        }
        for (int i = 0; i < keys.length; ++i) {
            if (PHYSICAL_KEY_EXISTS[keys[i]]) {
                return true;
            }
        }
        Logger.err.println("** " + catName + " has no physical keys but with factor " + factor + "%.");
        return false;
    }

    /**
     * See if any key exists for non-zero factors.
     * 检查无效值，主要检查3个
     *  NAV_KEYS
     *  MAJOR_NAV_KEYS
     *  SYS_KEYS
     */
    private boolean validateKeys() {
        return validateKeyCategory("NAV_KEYS", NAV_KEYS, mFactors[FACTOR_NAV])
            && validateKeyCategory("MAJOR_NAV_KEYS", MAJOR_NAV_KEYS, mFactors[FACTOR_MAJORNAV])
            && validateKeyCategory("SYS_KEYS", SYS_KEYS, mFactors[FACTOR_SYSOPS]);
    }

    /**
     * set the factors
     * 未使用……
     * @param factors percentages for each type of event
     */
    public void setFactors(float factors[]) {
        int c = FACTORZ_COUNT;
        if (factors.length < c) {
            c = factors.length;
        }
        for (int i = 0; i < c; i++)
            mFactors[i] = factors[i];
    }

    public void setFactors(int index, float v) {
        mFactors[index] = v;
    }

    /**
     * Generates a random motion event. This method counts a down, move, and up as multiple events.
     *
     * TODO:  Test & fix the selectors when non-zero percentages
     * TODO:  Longpress. 长按没有做
     * TODO:  Fling. 甩出去的动作没做
     * TODO:  Meta state
     * TODO:  More useful than the random walk here would be to pick a single random direction
     * and distance, and divvy it up into a random number of segments.  (This would serve to
     * generate fling gestures, which are important).
     * 用于构造事件（3种）点击、滑动、缩放
     * @param random Random number source for positioning 随机数量
     * @param gesture The gesture to perform. 需要的手势
     *
     */
    private void generatePointerEvent(Random random, int gesture) {
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY); //Display对象

        PointF p1 = randomPoint(random, display); //有个randomPint()方法，进去看看
        PointF v1 = randomVector(random);

        long downAt = SystemClock.uptimeMillis(); //自开机以来的时间戳

        mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_DOWN) //此处传入的按下的动作
                .setDownTime(downAt) //记录按下的时间戳
                .addPointer(0, p1.x, p1.y) //id都传0……，把获取到的x坐标与y坐标也传进去
                .setIntermediateNote(false)); //false表示这不是一个过渡事件
        //向双向链表中添加事件，添加一个元素，即MonkeyTouchEvent对象

        // sometimes we'll move during the touch
        if (gesture == GESTURE_DRAG) { //判断手势为拖拽，会走之类
            int count = random.nextInt(10);
            for (int i = 0; i < count; i++) {
                randomWalk(random, display, p1, v1);

                mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_MOVE)
                        .setDownTime(downAt)
                        .addPointer(0, p1.x, p1.y)
                        .setIntermediateNote(true));
            }
        } else if (gesture == GESTURE_PINCH_OR_ZOOM) { //缩放手势会走这里
            PointF p2 = randomPoint(random, display);
            PointF v2 = randomVector(random);

            randomWalk(random, display, p1, v1);
            mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_POINTER_DOWN
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true));

            int count = random.nextInt(10);
            for (int i = 0; i < count; i++) {
                randomWalk(random, display, p1, v1);
                randomWalk(random, display, p2, v2);

                mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_MOVE)
                        .setDownTime(downAt)
                        .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                        .setIntermediateNote(true));
            }

            randomWalk(random, display, p1, v1);
            randomWalk(random, display, p2, v2);
            mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_POINTER_UP
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true));
        }

        randomWalk(random, display, p1, v1);
        mQ.addLast(new MonkeyTouchEvent(MotionEvent.ACTION_UP)
                .setDownTime(downAt) //为啥还记录的按下的时间？
                .addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false)); //最后再添加一个ACTION_UP事件，如果是点事件，则至少添加了两个元素对象到mQ中，一个ACTION_DOWN、一个ACTION_UP、并且不是过渡事件
    }

    /**
     *
     * @param random
     * @param display 显示对象，知道屏幕的宽和高
     * @return 返回一个PointF对象，表示一个点？坐标在显示屏幕的坐标范围内
     */
    private PointF randomPoint(Random random, Display display) {
        return new PointF(random.nextInt(display.getWidth()), random.nextInt(display.getHeight())); //获取屏幕宽度与屏幕高度，在此范围随机获取各自一个值，并创建PointF对象

    }

    /**
     *
     * @param random Random对象
     * @return 再次返回一个PointF，这次获得的点，貌似坐标比较大？都乘了50，没有使用屏幕的相关信息
     */
    private PointF randomVector(Random random) {
        return new PointF((random.nextFloat() - 0.5f) * 50, (random.nextFloat() - 0.5f) * 50);
    }

    private void randomWalk(Random random, Display display, PointF point, PointF vector) {
        point.x = (float) Math.max(Math.min(point.x + random.nextFloat() * vector.x,
                display.getWidth()), 0);
        point.y = (float) Math.max(Math.min(point.y + random.nextFloat() * vector.y,
                display.getHeight()), 0);
    }

    /**
     * Generates a random trackball event. This consists of a sequence of small moves, followed by
     * an optional single click.
     *
     * TODO:  Longpress.
     * TODO:  Meta state
     * TODO:  Parameterize the % clicked
     * TODO:  More useful than the random walk here would be to pick a single random direction
     * and distance, and divvy it up into a random number of segments.  (This would serve to
     * generate fling gestures, which are important).
     *
     * @param random Random number source for positioning
     *
     */
    private void generateTrackballEvent(Random random) {
        for (int i = 0; i < 10; ++i) { //重复10次，意味着，每次添加10个轨迹球MOVE事件
            // generate a small random step
            int dX = random.nextInt(10) - 5; //随机范围非常克制
            int dY = random.nextInt(10) - 5;

            mQ.addLast(new MonkeyTrackballEvent(MotionEvent.ACTION_MOVE) //ACTION_MOVE代表轨迹球的移动事件
                    .addPointer(0, dX, dY)
                    .setIntermediateNote(i > 0));
        }

        // 10% of trackball moves end with a click ，相当于10%的概率（0-9）
        if (0 == random.nextInt(10)) { //只有随机到0的时候，才会构造轨迹球的点击事件
            long downAt = SystemClock.uptimeMillis(); //记录按下的时间戳

            mQ.addLast(new MonkeyTrackballEvent(MotionEvent.ACTION_DOWN) //ACTION_DOWN代表轨迹球的事件下
                    .setDownTime(downAt)
                    .addPointer(0, 0, 0)
                    .setIntermediateNote(true));

            mQ.addLast(new MonkeyTrackballEvent(MotionEvent.ACTION_UP) //ACTION_UP代表轨迹球的事件上
                    .setDownTime(downAt)
                    .addPointer(0, 0, 0)
                    .setIntermediateNote(false));
        }
    }

    /**
     * Generates a random screen rotation event.
     *
     * @param random Random number source for rotation degree.
     */
    private void generateRotationEvent(Random random) {
        mQ.addLast(new MonkeyRotationEvent(
                SCREEN_ROTATION_DEGREES[random.nextInt(
                        SCREEN_ROTATION_DEGREES.length)],
                random.nextBoolean()));
    }

    /**
     * generate a random event based on mFactor
     * 按照基础比例去构造不同的事件
     */
    private void generateEvents() {
        float cls = mRandom.nextFloat(); //先通过Random对象创建一个随机的float数
        int lastKey = 0; //用于记录最后一次的key，默认值为0

        if (cls < mFactors[FACTOR_TOUCH]) { //如果随机值小于TOUCH的比例值，比如比例值是30，那么此时会走这里
            generatePointerEvent(mRandom, GESTURE_TAP); //构造Pointer事件（点击事件）
            return; //方法结束…说明构造事件结束…如果TOUCH比例大于MOTION的话，岂不是MOTION一辈子没有机会了？
        } else if (cls < mFactors[FACTOR_MOTION]) { //比如这里是20，这里一辈子也没机会执行了（待定，觉得作者会对比例值做二次更新）
            generatePointerEvent(mRandom, GESTURE_DRAG); //构建拖拽事件
            return; //构建完毕
        } else if (cls < mFactors[FACTOR_PINCHZOOM]) { //构建缩放事件
            generatePointerEvent(mRandom, GESTURE_PINCH_OR_ZOOM);
            return; //构建完毕
        } else if (cls < mFactors[FACTOR_TRACKBALL]) { //构建轨迹球事件
            generateTrackballEvent(mRandom);
            return; //构建完毕
        } else if (cls < mFactors[FACTOR_ROTATION]) { //构建旋转事件
            generateRotationEvent(mRandom);
            return; //构建完毕
        } else if (cls < mFactors[FACTOR_PERMISSION]) { //构建权限事件
            mQ.add(mPermissionUtil.generateRandomPermissionEvent(mRandom));
            return; //构建完毕
        }

        // The remaining event categories are injected as key events
        for (;;) { //一直重试是为了防止出现POWER事件、
            if (cls < mFactors[FACTOR_NAV]) { //构建导航键事件
                lastKey = NAV_KEYS[mRandom.nextInt(NAV_KEYS.length)];
            } else if (cls < mFactors[FACTOR_MAJORNAV]) {  //构建主要导航键事件
                lastKey = MAJOR_NAV_KEYS[mRandom.nextInt(MAJOR_NAV_KEYS.length)];
            } else if (cls < mFactors[FACTOR_SYSOPS]) {
                lastKey = SYS_KEYS[mRandom.nextInt(SYS_KEYS.length)];
            } else if (cls < mFactors[FACTOR_APPSWITCH]) { //构建某个根Activity事件，注意这里不是切换单个应用哪个Activity的事件……
                MonkeyActivityEvent e = new MonkeyActivityEvent(mMainApps.get(
                        mRandom.nextInt(mMainApps.size())));
                mQ.addLast(e);
                return; //构建完毕
            } else if (cls < mFactors[FACTOR_FLIP]) { //构建键盘事件
                MonkeyFlipEvent e = new MonkeyFlipEvent(mKeyboardOpen);
                mKeyboardOpen = !mKeyboardOpen;
                mQ.addLast(e);
                return; //构建完毕
            } else {
                lastKey = 1 + mRandom.nextInt(KeyEvent.getMaxKeyCode() - 1); //使用一个随机的KeyCode值，赋值给表示最后一个实体按键事件
            }

            if (lastKey != KeyEvent.KEYCODE_POWER //如果生成的按键不是POWER、ENDCALL、SLEEP、SOFT_SLEEP，且keycode值在PHYSICAL_KEY_EXISTS标记为true，才会停止构造事件
                    && lastKey != KeyEvent.KEYCODE_ENDCALL
                    && lastKey != KeyEvent.KEYCODE_SLEEP
                    && lastKey != KeyEvent.KEYCODE_SOFT_SLEEP
                    && PHYSICAL_KEY_EXISTS[lastKey]) {
                break;
            }
        }

        MonkeyKeyEvent e = new MonkeyKeyEvent(KeyEvent.ACTION_DOWN, lastKey);
        mQ.addLast(e);

        e = new MonkeyKeyEvent(KeyEvent.ACTION_UP, lastKey);
        mQ.addLast(e);
    }

    /**
     * 用于计算事件比例是否合理
     * @return
     */
    public boolean validate() {
        boolean ret = true;
        // only populate & dump permissions if enabled
        if (mFactors[FACTOR_PERMISSION] != 0.0f) { //如果设置了PERMISSION事件才会走这里，注意这个FACTOR_PERMISSION，在MonkeySourceRandom中默认值为0
            ret &= mPermissionUtil.populatePermissionsMapping();
            if (ret && mVerbose >= 2) { //只有获取到权限与日志等级最高时
                mPermissionUtil.dump(); //输出更多日志
            }
        }
        return ret & adjustEventFactors(); //开始调整事件比例，返回检查结果，通过ret与，两个boolean值必须都计算，且都为true
    }

    public void setVerbose(int verbose) {
        mVerbose = verbose;
    }

    /**
     * generate an activity event 生成activity事件
     */
    public void generateActivity() {
        MonkeyActivityEvent e = new MonkeyActivityEvent(mMainApps.get(
                mRandom.nextInt(mMainApps.size()))); //MonkeySourceRandom对象持有的可用Activity的List对象中，随机选择一个ComponentName，创建一个MonkeyActivityEvent对象，随机范围是主Activity的数量，要是1个，那就是1个……
        mQ.addLast(e); //将事件添加到双向链表的尾部
    }

    /**
     * monkey主线程会一直调用该方法,获取MonkeyEvent对象
     * if the queue is empty, we generate events first 如果双联链表表示的队列是空的，就构造一个事件……
     * @return the first event in the queue 返回双向链表中的第一个事件
     */
    public MonkeyEvent getNextEvent() {
        if (mQ.isEmpty()) { //当双向链表中没有元素时，说明没有可用的事件
            generateEvents(); //构造事件，可能构造一个，也可能构造多个，构造的事件对象会添加到mQ中
        }
        mEventCount++; //MonkeySourceRandom对象持有的事件数量增加1，表示已经提取出的事件数量
        MonkeyEvent e = mQ.getFirst(); //获取双向链表中的第一个元素，赋值给局部变量e保存
        mQ.removeFirst(); //删除掉该事件（第一个元素）
        return e; //向调用者返回MonkeyEvent对象
    }
}
