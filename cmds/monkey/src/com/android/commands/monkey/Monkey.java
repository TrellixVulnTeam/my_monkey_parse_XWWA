/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commands.monkey;

import android.app.ActivityManager;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.view.IWindowManager;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Application that injects random key events and other actions into the system.
 */
public class Monkey {

    /**
     * Monkey Debugging/Dev Support
     * <p>
     * All values should be zero when checking in.
     */
    private final static int DEBUG_ALLOW_ANY_STARTS = 0; //允许的调试选项?

    private final static int DEBUG_ALLOW_ANY_RESTARTS = 0; //允许的调试选项？

    private IActivityManager mAm; //使用AMS服务,IActivityManager封装AMS提供哪些服务，一个IActivityManager对象，所有实现该接口的对象均可

    private IWindowManager mWm; //使用WMS服务，IWindowManager规定了WMS提供了哪些服务

    private IPackageManager mPm; //使用PMS服务，IPackageManager规定了PMS提供哪些服务

    /** Command line arguments */
    private String[] mArgs; //命令行参数

    /** Current argument being parsed */
    private int mNextArg; //用于指向数组中的某个命令行参数，第一个命令行参数的下标是0

    /** Data of current argument */
    private String mCurArgData;

    /** Running in verbose output mode? 1= verbose, 2=very verbose */
    private int mVerbose; //日志等级：1、verbose 2、very verbose 3、very very verbose

    /** Ignore any application crashes while running? */
    private boolean mIgnoreCrashes; //是否忽略App层的崩溃，不然monkey进程会停止

    /** Ignore any not responding timeouts while running? */
    private boolean mIgnoreTimeouts; //是否忽略运行超时？

    /** Ignore security exceptions when launching activities */
    /** (The activity launch still fails, but we keep pluggin' away) */
    private boolean mIgnoreSecurityExceptions; //是否忽略安全异常?

    /** Monitor /data/tombstones and stop the monkey if new files appear. */
    private boolean mMonitorNativeCrashes; //是否需要监控/data/tombstones目录（监控native异常）

    /** Ignore any native crashes while running? */
    private boolean mIgnoreNativeCrashes; //忽略任何native异常

    /** Send no events. Use with long throttle-time to watch user operations */
    private boolean mSendNoEvents;

    /** This is set when we would like to abort the running of the monkey. */
    private boolean mAbort; //是否支持中断monkey进程

    /**
     * Count each event as a cycle. Set to false for scripts so that each time
     * through the script increments the count.
     */
    private boolean mCountEvents = true; //是否计算循环执行事件的次数

    /**
     * This is set by the ActivityController thread to request collection of ANR
     * trace files
     */
    private boolean mRequestAnrTraces = false; //是否需要ANR的trace文件

    /**
     * This is set by the ActivityController thread to request a
     * "dumpsys meminfo"
     */
    private boolean mRequestDumpsysMemInfo = false; //是否需要内存信息

    /**
     * This is set by the ActivityController thread to request a
     * bugreport after ANR
     */
    private boolean mRequestAnrBugreport = false; //是否需要anr的报告

    /**
     * This is set by the ActivityController thread to request a
     * bugreport after a system watchdog report
     */
    private boolean mRequestWatchdogBugreport = false; //是否需要wathdog报告

    /**
     * Synchronization for the ActivityController callback to block
     * until we are done handling the reporting of the watchdog error.
     */
    private boolean mWatchdogWaiting = false; //是否需要线程等待watchdog报告的完成（同步要求）

    /**
     * This is set by the ActivityController thread to request a
     * bugreport after java application crash
     */
    private boolean mRequestAppCrashBugreport = false; //是否需要app崩溃的报告

    /**Request the bugreport based on the mBugreportFrequency. */
    private boolean mGetPeriodicBugreport = false; //

    /**
     * Request the bugreport based on the mBugreportFrequency.
     */
    private boolean mRequestPeriodicBugreport = false;

    /** Bugreport frequency. */
    private long mBugreportFrequency = 10;

    /** Failure process name */
    private String mReportProcessName; //用于存储上报进程的名字

    /**
     * This is set by the ActivityController thread to request a "procrank"
     */
    private boolean mRequestProcRank = false;

    /** Kill the process after a timeout or crash. */
    private boolean mKillProcessAfterError; //用于标记是否再崩溃后，中断monkey进程

    /** Generate hprof reports before/after monkey runs */
    private boolean mGenerateHprof;

    /** If set, only match error if this text appears in the description text. */
    private String mMatchDescription; //biao'ji

    /** Package denylist file. */
    private String mPkgBlacklistFile;

    /** Package allowlist file. */
    private String mPkgWhitelistFile;

    /** Categories we are allowed to launch **/
    private ArrayList<String> mMainCategories = new ArrayList<String>(); //分类用的list对象

    /** Applications we can switch to. */
    private ArrayList<ComponentName> mMainApps = new ArrayList<ComponentName>(); //存储组件名的list对象（app）

    /** The delay between event inputs **/
    long mThrottle = 0; //事件的延迟时间

    /** Whether to randomize each throttle (0-mThrottle ms) inserted between events. */
    boolean mRandomizeThrottle = false; //是否需要0-xx毫秒的随机延迟时间

    /** The number of iterations **/
    int mCount = 1000;

    /** The random number seed **/
    long mSeed = 0;

    /** The random number generator **/
    Random mRandom = null;

    /** Dropped-event statistics **/
    long mDroppedKeyEvents = 0;

    long mDroppedPointerEvents = 0;

    long mDroppedTrackballEvents = 0;

    long mDroppedFlipEvents = 0;

    long mDroppedRotationEvents = 0;

    /** The delay between user actions. This is for the scripted monkey. **/
    long mProfileWaitTime = 5000;

    /** Device idle time. This is for the scripted monkey. **/
    long mDeviceSleepTime = 30000;

    boolean mRandomizeScript = false;

    boolean mScriptLog = false;

    /** Capture bugreprot whenever there is a crash. **/
    private boolean mRequestBugreport = false;

    /** a filename to the setup script (if any) */
    private String mSetupFileName = null;

    /** filenames of the script (if any) */
    private ArrayList<String> mScriptFileNames = new ArrayList<String>();

    /** a TCP port to listen on for remote commands. */
    private int mServerPort = -1;

    private static final File TOMBSTONES_PATH = new File("/data/tombstones"); //native崩溃日志目录

    private static final String TOMBSTONE_PREFIX = "tombstone_";

    private static int NUM_READ_TOMBSTONE_RETRIES = 5;

    private HashSet<Long> mTombstones = null;

    float[] mFactors = new float[MonkeySourceRandom.FACTORZ_COUNT]; //创建一个float数组对象，存放12个元素，每个元素值表示某个事件的比例，不同的下标代表不同的事件类型

    MonkeyEventSource mEventSource; //持有的MonkeyEventSource对象（实际对象为子类对象，即MonkeySourceNetwork……

    private MonkeyNetworkMonitor mNetworkMonitor = new MonkeyNetworkMonitor(); //持有的MonkeyNetworkMonitor对象，用于监控网络

    private boolean mPermissionTargetSystem = false;

    // information on the current activity.
    public static Intent currentIntent; //Monkey类持有的currentIntent，表示当前发出的Intent

    public static String currentPackage; //Monkey类持有的currentPackage，表示当前操作包名

    /**
     * Monitor operations happening in the system. //Binder对象
     * Activity控制的Binder对象，这些方法应该会被AMS系统服务（单独线程中回调）
     */
    private class ActivityController extends IActivityController.Stub {
        /**
         *  当某个Activity启动时，AMS线程会回调此方法
         * @param intent 启动的Intent对象
         * @param pkg 启动的包名
         * @return
         */
        public boolean activityStarting(Intent intent, String pkg) {
            final boolean allow = isActivityStartingAllowed(intent, pkg); //allow表示是否可以启动Activity
            if (mVerbose > 0) {
                // StrictMode's disk checks end up catching this on
                // userdebug/eng builds due to PrintStream going to a
                // FileOutputStream in the end (perhaps only when
                // redirected to a file?)  So we allow disk writes
                // around this region for the monkey to minimize
                // harmless dropbox uploads from monkeys.
                StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskWrites();
                Logger.out.println("    // " + (allow ? "Allowing" : "Rejecting") + " start of "
                        + intent + " in package " + pkg);
                StrictMode.setThreadPolicy(savedPolicy);
            }
            currentPackage = pkg; //将AMS启动的包名保存到currentPackage中，Monkey知道正在启动哪个包
            currentIntent = intent; //将启动Activity的Intent对象也保存到这里一个
            return allow;
        }

        /**
         *  用于检查Activity是否允许启动
         * @param intent 传入的Intent对象
         * @param pkg 传入包名
         * @return
         */
        private boolean isActivityStartingAllowed(Intent intent, String pkg) {
            if (MonkeyUtils.getPackageFilter().checkEnteringPackage(pkg)) {
                return true;
            }
            if (DEBUG_ALLOW_ANY_STARTS != 0) {
                return true;
            }
            // In case the activity is launching home and the default launcher
            // package is disabled, allow anyway to prevent ANR (see b/38121026)
            final Set<String> categories = intent.getCategories();
            if (intent.getAction() == Intent.ACTION_MAIN
                    && categories != null
                    && categories.contains(Intent.CATEGORY_HOME)) {
                try {
                    final ResolveInfo resolveInfo =
                            mPm.resolveIntent(intent, intent.getType(), 0,
                                    ActivityManager.getCurrentUser());
                    final String launcherPackage = resolveInfo.activityInfo.packageName;
                    if (pkg.equals(launcherPackage)) {
                        return true;
                    }
                } catch (RemoteException e) {
                    Logger.err.println("** Failed talking with package manager!");
                    return false;
                }
            }
            return false;
        }

        /**
         * 当一个Activity准备好，AMS会回调此方法
         * @param pkg
         * @return
         */
        public boolean activityResuming(String pkg) {
            StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskWrites(); //与严格模式有关……
            Logger.out.println("    // activityResuming(" + pkg + ")");  //输出日志
            boolean allow = MonkeyUtils.getPackageFilter().checkEnteringPackage(pkg)
                    || (DEBUG_ALLOW_ANY_RESTARTS != 0);
            if (!allow) { //如果不同意启动Monkey
                if (mVerbose > 0) {
                    Logger.out.println("    // " + (allow ? "Allowing" : "Rejecting")
                            + " resume of package " + pkg);
                }
            }
            currentPackage = pkg;
            StrictMode.setThreadPolicy(savedPolicy); //还是严格模式
            return allow; //返回Activity的启动结果
        }

        /**
         * 出现App崩溃时，AMS系统服务会回调此方法，此方法运行在当前Monkey进程的binder线程池中，AMS牛逼，知道哪个进程崩溃了
         * @param processName 进程名
         * @param pid 进程的pid
         * @param shortMsg 短的堆栈信息
         * @param longMsg 长的堆栈信息
         * @param timeMillis 时间戳
         * @param stackTrace 堆栈信息
         * @return
         */
        public boolean appCrashed(String processName, int pid,
                String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskWrites(); //
            Logger.err.println("// CRASH: " + processName + " (pid " + pid + ")"); //向标准错误流中输出日志
            Logger.err.println("// Short Msg: " + shortMsg);
            Logger.err.println("// Long Msg: " + longMsg);
            Logger.err.println("// Build Label: " + Build.FINGERPRINT);
            Logger.err.println("// Build Changelist: " + Build.VERSION.INCREMENTAL);
            Logger.err.println("// Build Time: " + Build.TIME);
            Logger.err.println("// " + stackTrace.replace("\n", "\n// "));
            StrictMode.setThreadPolicy(savedPolicy);

            if (mMatchDescription == null
                    || shortMsg.contains(mMatchDescription)
                    || longMsg.contains(mMatchDescription)
                    || stackTrace.contains(mMatchDescription)) {
                if (!mIgnoreCrashes || mRequestBugreport) {
                    synchronized (Monkey.this) { //线程间同步，appCrashed方法在自己的Binder线程池里运行，这样Binder线程池里的线程会与Monkey的主线程竞争同一个对象锁
                                                 //Monkey主线程，每循环一次才会释放一次Monkey对象锁，如果Monkey主线程一直持有的Monkey对象不放，则Binder线程池里的线程会一直被阻塞，等待这个Monkey对象锁
                        if (!mIgnoreCrashes) { //如果没有忽略崩溃选项
                            mAbort = true; //设置Monkey进程会被中断
                        }
                        if (mRequestBugreport){ //如果用户设置了需要崩溃报告
                            mRequestAppCrashBugreport = true; //设置需要上报App崩溃的标志位，monkey主进程会在循环中读取这个值
                            mReportProcessName = processName; //设置需要上报的进程名字
                        }
                    } //这里，Binder线程池中的线程，会释放对象锁，Monkey主进程会继续执行（用对象锁，做的线程间同步）
                    return !mKillProcessAfterError; //这个值，是给AMS用的呀……这里暂时有点蒙蔽
                }
            }
            return false;
        }

        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            return 0;
        }

        /**
         * 发生ANR时，AMS回调此方法，应该是在binder线程池中的某个线程中执行吧
         * @param processName 进程名
         * @param pid 进程id
         * @param processStats 进程状态
         * @return
         */
        public int appNotResponding(String processName, int pid, String processStats) {
            StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskWrites();
            Logger.err.println("// NOT RESPONDING: " + processName + " (pid " + pid + ")");
            Logger.err.println(processStats);
            StrictMode.setThreadPolicy(savedPolicy);

            if (mMatchDescription == null || processStats.contains(mMatchDescription)) {
                synchronized (Monkey.this) {
                    mRequestAnrTraces = true;
                    mRequestDumpsysMemInfo = true;
                    mRequestProcRank = true;
                    if (mRequestBugreport) {
                        mRequestAnrBugreport = true;
                        mReportProcessName = processName;
                    }
                }
                if (!mIgnoreTimeouts) {
                    synchronized (Monkey.this) {
                        mAbort = true;
                    }
                }
            }

            return (mKillProcessAfterError) ? -1 : 1;
        }

        /**
         * 系统没响应时，AMS会回调此方法
         * @param message 没响应的原因
         * @return 返回的数字，表示退出状态码
         */
        public int systemNotResponding(String message) {
            StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskWrites();
            Logger.err.println("// WATCHDOG: " + message);
            StrictMode.setThreadPolicy(savedPolicy);

            synchronized (Monkey.this) {
                if (mMatchDescription == null || message.contains(mMatchDescription)) {
                    if (!mIgnoreCrashes) {
                        mAbort = true;
                    }
                    if (mRequestBugreport) {
                        mRequestWatchdogBugreport = true;
                    }
                }
                mWatchdogWaiting = true;
            }
            synchronized (Monkey.this) {
                while (mWatchdogWaiting) {
                    try {
                        Monkey.this.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            return (mKillProcessAfterError) ? -1 : 1;
        }
    }

    /**
     * Run the procrank tool to insert system status information into the debug
     * report.
     * 报告名字为procrank
     * 工具名称也为procrank，看来使用的是一个命令行工具
     */
    private void reportProcRank() {
        commandLineReport("procrank", "procrank");
    }

    /**
     * Dump the most recent ANR trace. Wait about 5 seconds first, to let the
     * asynchronous report writing complete.
     * 生成最近的ANR trace，先等5秒，让异步报告先写入完成（Monkey主线程会等待5秒）
     */
    private void reportAnrTraces() {
        try {
            Thread.sleep(5 * 1000);
        } catch (InterruptedException e) {
        }

        // The /data/anr directory might have multiple files, dump the most
        // recent of those files.
        File[] recentTraces = new File("/data/anr/").listFiles(); //先获取/data/anr/下的所有目录与文件，listFiles（）返回的是File数组对象
        if (recentTraces != null) { //当确实存在文件
            File mostRecent = null;
            long mostRecentMtime = 0;
            for (File trace : recentTraces) { //遍历每一个文件
                final long mtime = trace.lastModified(); //获取上一次的修改时间
                if (mtime > mostRecentMtime) {
                    mostRecentMtime = mtime;
                    mostRecent = trace;
                }
            }

            if (mostRecent != null) {
                commandLineReport("anr traces", "cat " + mostRecent.getAbsolutePath()); //竟然使用的是cat命令
            }
        }
    }

    /**
     * Run "dumpsys meminfo"
     * <p>
     * NOTE: You cannot perform a dumpsys call from the ActivityController
     * callback, as it will deadlock. This should only be called from the main
     * loop of the monkey.
     */
    private void reportDumpsysMemInfo() {
        commandLineReport("meminfo", "dumpsys meminfo");
    }

    /**
     * Print report from a single command line.
     * <p>
     * TODO: Use ProcessBuilder & redirectErrorStream(true) to capture both
     * streams (might be important for some command lines)
     *
     * @param reportName Simple tag that will print before the report and in
     *            various annotations. 文件名称
     * @param command Command line to execute. 执行的可执行文件命令
     */
    private void commandLineReport(String reportName, String command) {
        Logger.err.println(reportName + ":");
        Runtime rt = Runtime.getRuntime();
        Writer logOutput = null;

        try {
            // Process must be fully qualified here because android.os.Process
            // is used elsewhere
            java.lang.Process p = Runtime.getRuntime().exec(command); //子进程执行命令，替换命令，进程中执行某个程序

            if (mRequestBugreport) {
                logOutput =
                        new BufferedWriter(new FileWriter(new File(Environment
                                .getLegacyExternalStorageDirectory(), reportName), true));
            }
            // pipe everything from process stdout -> System.err
            InputStream inStream = p.getInputStream(); //子进程的标准输入流……，我猜测Monkey主进程会等待子进程完成工作
            InputStreamReader inReader = new InputStreamReader(inStream);
            BufferedReader inBuffer = new BufferedReader(inReader);
            String s;
            while ((s = inBuffer.readLine()) != null) {
                if (mRequestBugreport) {
                    try {
                        // When no space left on the device the write will
                        // occurs an I/O exception, so we needed to catch it
                        // and continue to read the data of the sync pipe to
                        // aviod the bugreport hang forever.
                        logOutput.write(s);
                        logOutput.write("\n");
                    } catch (IOException e) {
                        while(inBuffer.readLine() != null) {}
                        Logger.err.println(e.toString());
                        break;
                    }
                } else {
                    Logger.err.println(s);
                }
            }

            int status = p.waitFor(); //就是在这里，Monkey进程会等待，子进程完成工作（进程间同步），这就是Monkey的工作机制
            Logger.err.println("// " + reportName + " status was " + status); //子进程完成工作后，向标准错误流中打印日志，以及状态

            if (logOutput != null) {
                logOutput.close();
            }
        } catch (Exception e) {
            Logger.err.println("// Exception from " + reportName + ":");
            Logger.err.println(e.toString());
        }
    }

    // Write the numbe of iteration to the log

    /**
     *
     * @param count 表示事件数量
     */
    private void writeScriptLog(int count) {
        // TO DO: Add the script file name to the log.
        try {
            Writer output = new BufferedWriter(new FileWriter(new File(
                    Environment.getLegacyExternalStorageDirectory(), "scriptlog.txt"), true));
            output.write("iteration: " + count + " time: "
                    + MonkeyUtils.toCalendarTime(System.currentTimeMillis()) + "\n");
            output.close();
        } catch (IOException e) {
            Logger.err.println(e.toString());
        }
    }

    // Write the bugreport to the sdcard，把bug report存储到SDk卡

    /**
     *
     * @param reportName 报告的名字
     */
    private void getBugreport(String reportName) {
        reportName += MonkeyUtils.toCalendarTime(System.currentTimeMillis()); //再将文件名处增加一个生成的时间
        String bugreportName = reportName.replaceAll("[ ,:]", "_"); //把所有的空格字符、逗号、冒号，全部替换成下划线_
        commandLineReport(bugreportName + ".txt", "bugreport"); //使用shell命令，生成文件（在进程中运行）
    }

    /**
     * Command-line entry point.
     * 主线程中执行…………看看大佬的monkey怎么写的……
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        // Set the process name showing in "ps" or "top"
        Process.setArgV0("com.android.commands.monkey"); //修改monkey程序的进程名称

        Logger.err.println("args: " + Arrays.toString(args)); //向标准错误流，输出命令行参数信息
        int resultCode = (new Monkey()).run(args); //创建Monkey对象,调用run（）方法，将数组对象（命令行参数）传进去
        System.exit(resultCode); //退出虚拟机进程，返回退出状态码
    }

    /**
     * Run the command!
     * 执行monkey命令
     * @param args The command-line arguments 命令行参数
     * @return Returns a posix-style result code. 0 for no error. 返回一个退出状态码，0表示没有错误
     */
    private int run(String[] args) {
        // Super-early debugger wait
        for (String s : args) { //遍历所有命令行参数
            if ("--wait-dbg".equals(s)) {
                Debug.waitForDebugger(); //包含--wait-dbg参数时，执行Debugger操作
            }
        }

        // Default values for some command-line options 命令行选项赋初始值（将命令行参数保存到内存中）
        mVerbose = 0; //日志等级默认值为0
        mCount = 1000; //次数默认值为0
        mSeed = 0; //随机种子值为0
        mThrottle = 0; //延迟时间默认为0

        // prepare for command-line processing
        mArgs = args; //由实例变量mArgs开始持有包含所有命令行参数，它是一个String数组对象
        for (String a: args) {
            Logger.err.println(" arg: \"" + a + "\""); //遍历所有命令行参数，向标准错误流中输出
        }
        mNextArg = 0; //用于指向某个命令行参数，默认指向第一个命令行参数

        // set a positive value, indicating none of the factors is provided yet
        for (int i = 0; i < MonkeySourceRandom.FACTORZ_COUNT; i++) {
            mFactors[i] = 1.0f; //为数组对象mFactors中的每个元素赋值为1.0f，下标0-11，全部赋值为1.0f
        }

        if (!processOptions()) { //检查并处理所有的命令行参数
            return -1; //如果命令行参数发生错误，返回退出状态码-1，这个退出状态码，shell可以拿到
        }

        if (!loadPackageLists()) { //检查并处理文件中持久化的包名（白名单文件、黑名单文件）看来除了命令行指定，还可以指定文件
            return -1;
        }

        // now set up additional data in preparation for launch 用于操作Launch……
        if (mMainCategories.size() == 0) { //第一次会添加两个元素，都时Categoryies……
            mMainCategories.add(Intent.CATEGORY_LAUNCHER);
            mMainCategories.add(Intent.CATEGORY_MONKEY);
        }

        if (mSeed == 0) { //随机种子没有设置时，使用时间戳+当前对象的hashCode值相加得到一个新的随机种子值
            mSeed = System.currentTimeMillis() + System.identityHashCode(this);
        }

        if (mVerbose > 0) {  //如果设置-v ,Logger是自己封装的Log工具类
            Logger.out.println(":Monkey: seed=" + mSeed + " count=" + mCount);
            MonkeyUtils.getPackageFilter().dump(); //输出一次设置的有效包名、无效包名
            if (mMainCategories.size() != 0) {
                Iterator<String> it = mMainCategories.iterator();
                while (it.hasNext()) { //遍历ArrayList
                    Logger.out.println(":IncludeCategory: " + it.next()); //输出日志
                }
            }
        }

        if (!checkInternalConfiguration()) { //目前还没有实现
            return -2; //内部配置出错，会返回-2
        }

        if (!getSystemInterfaces()) { //检查并初始化系统服务，这里非常重要，全依赖系统服务的远程Binder，完成工作呢
            return -3; //系统服务出错，会返回-3
        }

        if (!getMainApps()) {
            return -4; //主要包出错，会返回-4
        }

        mRandom = new Random(mSeed); //创建Random对象……随机种子传给它,伪随机……

        //初始化Monkey对象持有的mEventSource
        if (mScriptFileNames != null && mScriptFileNames.size() == 1) { //只有指定1个脚本文件时
            // script mode, ignore other options
            mEventSource = new MonkeySourceScript(mRandom, mScriptFileNames.get(0), mThrottle,
                    mRandomizeThrottle, mProfileWaitTime, mDeviceSleepTime); //创建MonkeySourceScript对象
            mEventSource.setVerbose(mVerbose); //设置MonkeySourceScript的监控等级

            mCountEvents = false; //无需计算事件的次数
        } else if (mScriptFileNames != null && mScriptFileNames.size() > 1) { //当指定多个脚本文件时
            if (mSetupFileName != null) { //已经初始化脚本文件
                mEventSource = new MonkeySourceRandomScript(mSetupFileName,
                        mScriptFileNames, mThrottle, mRandomizeThrottle, mRandom,
                        mProfileWaitTime, mDeviceSleepTime, mRandomizeScript);
                mCount++; //创建一个MonkeySourceRandomScript对象，猜测可以用来选择一个脚本文件，抽空再看这里的代码，这里为何mCount需要+1，
            } else { //没有初始化脚本的情况，此时mSetupFileName为null
                mEventSource = new MonkeySourceRandomScript(mScriptFileNames,
                        mThrottle, mRandomizeThrottle, mRandom,
                        mProfileWaitTime, mDeviceSleepTime, mRandomizeScript); //同样创建一个MonkeySourceRandomScript
            }
            mEventSource.setVerbose(mVerbose); //设置事件来源的日志等级（保持与Monkey中一样的等级）
            mCountEvents = false; //无需计算事件数量
        } else if (mServerPort != -1) { //TCP……，基于网络
            try {
                mEventSource = new MonkeySourceNetwork(mServerPort); //创建MonkeySourceNetwork对象
            } catch (IOException e) {
                Logger.out.println("Error binding to network socket.");
                return -5;
            }
            mCount = Integer.MAX_VALUE; //直接将数量设置为最大值
        } else { //没有脚本文件、没有基于网络、当基于命令行参数时，走这里
            // random source by default
            if (mVerbose >= 2) { // check seeding performance
                Logger.out.println("// Seeded: " + mSeed); //向标准输出流输出随机种子数，前提是日志等级大于2
            }
            mEventSource = new MonkeySourceRandom(mRandom, mMainApps,
                    mThrottle, mRandomizeThrottle, mPermissionTargetSystem); //创建MonkeySourceRandom对象，事件源最重要的对象
            mEventSource.setVerbose(mVerbose); //将命令行中解析的日志等级同样赋值给MonkeySourceRandom对象
            // set any of the factors that has been set
            // 遍历MonkeySourceRandom设置的12个事件
            for (int i = 0; i < MonkeySourceRandom.FACTORZ_COUNT; i++) {
                if (mFactors[i] <= 0.0f) { //遍历Monkey对象持有的数组对象mFactors
                    ((MonkeySourceRandom) mEventSource).setFactors(i, mFactors[i]); //将命令行中的指定的事件存放到MonkeySourceRandom对象持有的数组对象中
                    //只有用户指定的事件比例是个小于0的数字，这下子MonkeySourceRandom已经保存上用户需要事件比例了
                }
            }

            // in random mode, we start with a random activity，随机模式中，建立随机的Activity
            ((MonkeySourceRandom) mEventSource).generateActivity(); //生成Activity事件（首先启动Activity，这个没毛病）
        }

        // validate source generator 检查事件比例
        if (!mEventSource.validate()) {
            return -5; //事件比例错误，直接返回退出状态码为-5
        }

        // If we're profiling, do it immediately before/after the main monkey
        // loop
        // 检查是否需要构建堆信息
        if (mGenerateHprof) {
            signalPersistentProcesses();
        }

        mNetworkMonitor.start(); //开始监控网络,初始化一些时间，它是一个Binder
        int crashedAtCycle = 0; //存储循环中发现的崩溃数量
        try {
            crashedAtCycle = runMonkeyCycles(); //主线程，执行最重要的runMonkeyCycles（）方法，返回值是发现的崩溃数量
        } finally {
            // Release the rotation lock if it's still held and restore the
            // original orientation.
            new MonkeyRotationEvent(Surface.ROTATION_0, false).injectEvent(
                mWm, mAm, mVerbose); //Monkey完成的时候，注入一个MonkeyRotationEvent，为了调整屏幕吗？
        }
        mNetworkMonitor.stop(); //停止监控网络

        synchronized (this) {
            if (mRequestAnrTraces) {
                reportAnrTraces();
                mRequestAnrTraces = false;
            }
            if (mRequestAnrBugreport){
                Logger.out.println("Print the anr report");
                getBugreport("anr_" + mReportProcessName + "_");
                mRequestAnrBugreport = false;
            }
            if (mRequestWatchdogBugreport) {
                Logger.out.println("Print the watchdog report");
                getBugreport("anr_watchdog_");
                mRequestWatchdogBugreport = false;
            }
            if (mRequestAppCrashBugreport){
                getBugreport("app_crash" + mReportProcessName + "_");
                mRequestAppCrashBugreport = false;
            }
            if (mRequestDumpsysMemInfo) {
                reportDumpsysMemInfo();
                mRequestDumpsysMemInfo = false;
            }
            if (mRequestPeriodicBugreport){
                getBugreport("Bugreport_");
                mRequestPeriodicBugreport = false;
            }
            if (mWatchdogWaiting) {
                mWatchdogWaiting = false;
                notifyAll();
            }
        }

        if (mGenerateHprof) {
            signalPersistentProcesses();
            if (mVerbose > 0) {
                Logger.out.println("// Generated profiling reports in /data/misc");
            }
        }

        try {
            mAm.setActivityController(null, true);
            mNetworkMonitor.unregister(mAm);
        } catch (RemoteException e) {
            // just in case this was latent (after mCount cycles), make sure
            // we report it
            if (crashedAtCycle >= mCount) {
                crashedAtCycle = mCount - 1;
            }
        }

        // report dropped event stats
        if (mVerbose > 0) { //原来只是记录下来，告知用户哪些失败了……
            Logger.out.println(":Dropped: keys=" + mDroppedKeyEvents
                    + " pointers=" + mDroppedPointerEvents
                    + " trackballs=" + mDroppedTrackballEvents
                    + " flips=" + mDroppedFlipEvents
                    + " rotations=" + mDroppedRotationEvents);
        }

        // report network stats
        mNetworkMonitor.dump(); //报告网络状态

        if (crashedAtCycle < mCount - 1) {
            Logger.err.println("** System appears to have crashed at event " + crashedAtCycle
                    + " of " + mCount + " using seed " + mSeed);
            return crashedAtCycle;
        } else {
            if (mVerbose > 0) {
                Logger.out.println("// Monkey finished");
            }
            return 0;
        }
    }

    /**
     * Process the command-line options
     * 处理命令行参数，将命令行参数统统保存到内存中（由当前Monkey对象持有的实例变量负责保存）
     * @return Returns true if options were parsed with no apparent errors. 解析命令行参数的结果，没有错误时，返回true
     */
    private boolean processOptions() {
        // quick (throwaway) check for unadorned command
        if (mArgs.length < 1) { //命令行参数少于1个时
            showUsage(); //告知用户输出的参数
            return false; //返回值false 表示有错误
        }

        try {
            String opt; //临时局部变量，用于存储某个命令行参数
            Set<String> validPackages = new HashSet<>(); //创建一个Set对象，用于临时保存有效的包名
            while ((opt = nextOption()) != null) {
                if (opt.equals("-s")) { //当单个命令行参数为-s时
                    mSeed = nextOptionLong("Seed"); //此Seed仅为提示……，获取下个命令行参数，同时命令行参数下标+1
                } else if (opt.equals("-p")) {
                    validPackages.add(nextOptionData()); //可以看到-p参数后面的包名，会放到一个Set集合中，天生去重
                } else if (opt.equals("-c")) {
                    mMainCategories.add(nextOptionData()); //-c参数后面的参数放到了一个list中
                } else if (opt.equals("-v")) {
                    mVerbose += 1;// 一个-v参数，为其+1
                } else if (opt.equals("--ignore-crashes")) {
                    mIgnoreCrashes = true;
                } else if (opt.equals("--ignore-timeouts")) {
                    mIgnoreTimeouts = true;
                } else if (opt.equals("--ignore-security-exceptions")) {
                    mIgnoreSecurityExceptions = true;
                } else if (opt.equals("--monitor-native-crashes")) {
                    mMonitorNativeCrashes = true;
                } else if (opt.equals("--ignore-native-crashes")) {
                    mIgnoreNativeCrashes = true;
                } else if (opt.equals("--kill-process-after-error")) {
                    mKillProcessAfterError = true;
                } else if (opt.equals("--hprof")) {
                    mGenerateHprof = true;
                } else if (opt.equals("--match-description")) {
                    mMatchDescription = nextOptionData();
                } else if (opt.equals("--pct-touch")) { //触摸事件比例
                    int i = MonkeySourceRandom.FACTOR_TOUCH;
                    mFactors[i] = -nextOptionLong("touch events percentage"); //注意这里采用的负值，因为……没有通过命令行传入的值，默认值都是0.0
                } else if (opt.equals("--pct-motion")) {
                    int i = MonkeySourceRandom.FACTOR_MOTION;
                    mFactors[i] = -nextOptionLong("motion events percentage"); //为何解析完，存储一个负数呢？
                } else if (opt.equals("--pct-trackball")) {
                    int i = MonkeySourceRandom.FACTOR_TRACKBALL;
                    mFactors[i] = -nextOptionLong("trackball events percentage");
                } else if (opt.equals("--pct-rotation")) {
                    int i = MonkeySourceRandom.FACTOR_ROTATION;
                    mFactors[i] = -nextOptionLong("screen rotation events percentage");
                } else if (opt.equals("--pct-syskeys")) {
                    int i = MonkeySourceRandom.FACTOR_SYSOPS;
                    mFactors[i] = -nextOptionLong("system (key) operations percentage");
                } else if (opt.equals("--pct-nav")) {
                    int i = MonkeySourceRandom.FACTOR_NAV;
                    mFactors[i] = -nextOptionLong("nav events percentage");
                } else if (opt.equals("--pct-majornav")) {
                    int i = MonkeySourceRandom.FACTOR_MAJORNAV;
                    mFactors[i] = -nextOptionLong("major nav events percentage");
                } else if (opt.equals("--pct-appswitch")) {
                    int i = MonkeySourceRandom.FACTOR_APPSWITCH;
                    mFactors[i] = -nextOptionLong("app switch events percentage");
                } else if (opt.equals("--pct-flip")) {
                    int i = MonkeySourceRandom.FACTOR_FLIP;
                    mFactors[i] = -nextOptionLong("keyboard flip percentage");
                } else if (opt.equals("--pct-anyevent")) {
                    int i = MonkeySourceRandom.FACTOR_ANYTHING;
                    mFactors[i] = -nextOptionLong("any events percentage");
                } else if (opt.equals("--pct-pinchzoom")) {
                    int i = MonkeySourceRandom.FACTOR_PINCHZOOM;
                    mFactors[i] = -nextOptionLong("pinch zoom events percentage");
                } else if (opt.equals("--pct-permission")) {
                    int i = MonkeySourceRandom.FACTOR_PERMISSION;
                    mFactors[i] = -nextOptionLong("runtime permission toggle events percentage");
                } else if (opt.equals("--pkg-blacklist-file")) {
                    mPkgBlacklistFile = nextOptionData();
                } else if (opt.equals("--pkg-whitelist-file")) {
                    mPkgWhitelistFile = nextOptionData();
                } else if (opt.equals("--throttle")) {
                    mThrottle = nextOptionLong("delay (in milliseconds) to wait between events");
                } else if (opt.equals("--randomize-throttle")) {
                    mRandomizeThrottle = true;
                } else if (opt.equals("--wait-dbg")) {
                    // do nothing - it's caught at the very start of run()
                } else if (opt.equals("--dbg-no-events")) {
                    mSendNoEvents = true;
                } else if (opt.equals("--port")) {
                    mServerPort = (int) nextOptionLong("Server port to listen on for commands");
                } else if (opt.equals("--setup")) {
                    mSetupFileName = nextOptionData();
                } else if (opt.equals("-f")) {
                    mScriptFileNames.add(nextOptionData());
                } else if (opt.equals("--profile-wait")) {
                    mProfileWaitTime = nextOptionLong("Profile delay" +
                                " (in milliseconds) to wait between user action");
                } else if (opt.equals("--device-sleep-time")) {
                    mDeviceSleepTime = nextOptionLong("Device sleep time" +
                                                      "(in milliseconds)");
                } else if (opt.equals("--randomize-script")) {
                    mRandomizeScript = true;
                } else if (opt.equals("--script-log")) {
                    mScriptLog = true;
                } else if (opt.equals("--bugreport")) {
                    mRequestBugreport = true;
                } else if (opt.equals("--periodic-bugreport")){
                    mGetPeriodicBugreport = true;
                    mBugreportFrequency = nextOptionLong("Number of iterations");
                } else if (opt.equals("--permission-target-system")){
                    mPermissionTargetSystem = true;
                } else if (opt.equals("-h")) {
                    showUsage();
                    return false;
                } else {
                    Logger.err.println("** Error: Unknown option: " + opt);
                    showUsage();
                    return false; //表示解析参数有问题
                }
            }
            MonkeyUtils.getPackageFilter().addValidPackages(validPackages); //把临时保存的有效包名，存放到PackageFilter对象持有的set中，统一保管
        } catch (RuntimeException ex) { //捕获所有运行时异常，输出异常对象字符串信息
            Logger.err.println("** Error: " + ex.toString()); //** Error: java.lang.NumberFormatException: For input string: "x"
            showUsage();
            return false;
        }

        // If a server port hasn't been specified, we need to specify
        // a count
        if (mServerPort == -1) { //不使用TCP远程命令时，会走这里
            String countStr = nextArg();
            if (countStr == null) {
                Logger.err.println("** Error: Count not specified"); //看到你了
                showUsage();
                return false;
            }

            try {
                mCount = Integer.parseInt(countStr); //最后解析执行次数……执行次数必须放到最后……
            } catch (NumberFormatException e) {
                Logger.err.println("** Error: Count is not a number: \"" + countStr + "\"");
                showUsage();
                return false;
            }
        }

        return true; //正常情况下走这里
    }

    /**
     * Load a list of package names from a file. 从持久化的文件中加载包名
     *
     * @param fileName The file name, with package names separated by new line. 每行一个包名
     * @param list The destination list.
     * @return Returns false if any error occurs.
     */
    private static boolean loadPackageListFromFile(String fileName, Set<String> list) {
        BufferedReader reader = null; //缓存的字符流对象
        try {
            reader = new BufferedReader(new FileReader(fileName));
            String s;
            while ((s = reader.readLine()) != null) { //逐行读取
                s = s.trim(); //干掉空白字符
                if ((s.length() > 0) && (!s.startsWith("#"))) { //如果每行的长度大于0个，且不是以#开头的
                    list.add(s); //添加到集合中……，大哥，你这命名不要脸了啊
                }
            }
        } catch (IOException ioe) { //读取文件出现异常
            Logger.err.println("" + ioe); //标准错误流打印日志
            return false; //返回表示错误的结果
        } finally { //不管发生任何异常，字符流必须关闭，释放内存
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ioe) {
                    Logger.err.println("" + ioe);
                }
            }
        }
        return true;
    }

    /**
     * Load package denylist or allowlist (if specified).
     * 加载拒绝的包名与同意的包名，如果指定了
     * @return Returns false if any error occurs. //如果发生任何错误，则会返回false
     */
    private boolean loadPackageLists() {
        if (((mPkgWhitelistFile != null) || (MonkeyUtils.getPackageFilter().hasValidPackages()))
                && (mPkgBlacklistFile != null)) { //如果通过命令行指定了白名单的文件名，获取设置的有效的包名，且黑名单不为空
            Logger.err.println("** Error: you can not specify a package blacklist "
                    + "together with a whitelist or individual packages (via -p)."); //告知用户你不能再指定黑名单列表了
            return false;
        }
        Set<String> validPackages = new HashSet<>(); //临时创建一个HashSet对象，用于保存文件中的包名
        if ((mPkgWhitelistFile != null) //如果指定了白名单文件
                && (!loadPackageListFromFile(mPkgWhitelistFile, validPackages))) {
            return false;
        }
        MonkeyUtils.getPackageFilter().addValidPackages(validPackages);//把文件中的包名添加到包过滤器的集合中……
        Set<String> invalidPackages = new HashSet<>();
        if ((mPkgBlacklistFile != null) //如果指定了包含黑名单包名的文件
                && (!loadPackageListFromFile(mPkgBlacklistFile, invalidPackages))) {
            return false;
        }
        MonkeyUtils.getPackageFilter().addInvalidPackages(invalidPackages); //把黑名单文件中的包名，添加到包过滤器对象持有的无效包名集合中
        return true;
    }

    /**
     * Check for any internal configuration (primarily build-time) errors.
     * 写死了为true……默认就是各种爽
     * @return Returns true if ready to rock.
     */
    private boolean checkInternalConfiguration() {
        return true;
    }

    /**
     * Attach to the required system interfaces.
     *
     * @return Returns true if all system interfaces were available.
     * 初始化系统服务
     */
    private boolean getSystemInterfaces() {
        mAm = ActivityManager.getService(); //获取AMS系统服务
        if (mAm == null) {
            Logger.err.println("** Error: Unable to connect to activity manager; is the system "
                    + "running?"); //告知无法获取AMS服务，反问你系统到底有没有运行
            return false;
        }

        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window")); //获取WMS系统服务
        if (mWm == null) {
            Logger.err.println("** Error: Unable to connect to window manager; is the system "
                    + "running?");
            return false;
        }

        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package")); //获取PMS系统服务
        if (mPm == null) {
            Logger.err.println("** Error: Unable to connect to package manager; is the system "
                    + "running?");
            return false;
        }

        try {
            mAm.setActivityController(new ActivityController(), true);//向AMS传入一个Binder对象，AMS通过此Binder对象，与Monkey进程通信
            mNetworkMonitor.register(mAm); //将AMS服务注册到一个用于监听的网络Binder中
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with activity manager!"); //AMS服务挂了……
            return false;
        }

        return true;
    }

    /**
     * Using the restrictions provided (categories & packages), generate a list
     * of activities that we can actually switch to.
     * 这里不仅检查App是否存在，还构建出了该App对应的Activity
     * @return Returns true if it could successfully build a list of target
     *         activities
     */
    private boolean getMainApps() {
        try {
            final int N = mMainCategories.size();
            for (int i = 0; i < N; i++) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                String category = mMainCategories.get(i);
                if (category.length() > 0) {
                    intent.addCategory(category); //为Intent对象设置Category
                }
                List<ResolveInfo> mainApps = mPm.queryIntentActivities(intent, null, 0,
                        ActivityManager.getCurrentUser()).getList(); //查询可以使用Intent的Activity，坑爹……
                if (mainApps == null || mainApps.size() == 0) {
                    Logger.err.println("// Warning: no activities found for category " + category);
                    continue;
                }
                if (mVerbose >= 2) { // very verbose
                    Logger.out.println("// Selecting main activities from category " + category);
                }
                final int NA = mainApps.size();
                for (int a = 0; a < NA; a++) {
                    ResolveInfo r = mainApps.get(a);
                    String packageName = r.activityInfo.applicationInfo.packageName;
                    if (MonkeyUtils.getPackageFilter().checkEnteringPackage(packageName)) {
                        if (mVerbose >= 2) { // very verbose
                            Logger.out.println("//   + Using main activity " + r.activityInfo.name
                                    + " (from package " + packageName + ")");
                        }
                        mMainApps.add(new ComponentName(packageName, r.activityInfo.name));
                    } else {
                        if (mVerbose >= 3) { // very very verbose
                            Logger.out.println("//   - NOT USING main activity "
                                    + r.activityInfo.name + " (from package " + packageName + ")");
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with package manager!"); //PMS系统服务出错
            return false;
        }

        if (mMainApps.size() == 0) {
            Logger.out.println("** No activities found to run, monkey aborted."); //没有找到Activity，Monkey终止
            return false;
        }

        return true;
    }

    /**
     * Run mCount cycles and see if we hit any crashers.
     * <p>
     * TODO: Meta state on keys
     * 开始执行monkey
     *
     * @return Returns the last cycle which executed. If the value == mCount, no
     *         errors detected.
     * Monkey主线程会一直循环在这里执行
     *
     */
    private int runMonkeyCycles() {
        int eventCounter = 0; //临时存储事件总数
        int cycleCounter = 0; //临时存储循环次数

        boolean shouldReportAnrTraces = false; //记录是否应该报告ANR的标志位
        boolean shouldReportDumpsysMemInfo = false; //记录是否应该报告内存信息的标志位
        boolean shouldAbort = false; //记录是否应该中断monkey主线程的标志位
        boolean systemCrashed = false; //系统系统是否发生崩溃的标志位，比如AMS服务停止工作，那么Monkey只好停止了……有道理……

        try {
            // 1、系统本身未出现崩溃
            // 2、Monkey的执行次数未到
            // 两个条件同时满足时，monkey程序会一直运行
            // TO DO : The count should apply to each of the script file.
            while (!systemCrashed && cycleCounter < mCount) {
                synchronized (this) { //Monkey的主线程需要获取对象锁，才可继续运行（Monkey对象自身的锁)
                    if (mRequestProcRank) {
                        reportProcRank(); //报告进程评分，创建子进程，调用命令行工具
                        mRequestProcRank = false; //防止重复上报，因为程序在主线程循环中
                    }
                    if (mRequestAnrTraces) {//需要上报anr的anrTrace
                        mRequestAnrTraces = false; //这里防止多次设置标志位
                        shouldReportAnrTraces = true; //设置应该上报AnrTraces的标志位
                    }
                    if (mRequestAnrBugreport){ //需要上报anr的bugreport
                        getBugreport("anr_" + mReportProcessName + "_"); //报告的名字为anr_进程名_
                        mRequestAnrBugreport = false; //防止多次上报
                    }
                    if (mRequestWatchdogBugreport) { //需要上报watchdog的bugreport
                        Logger.out.println("Print the watchdog report");
                        getBugreport("anr_watchdog_");
                        mRequestWatchdogBugreport = false; //这个标志位会由AMS修改，跨进程的告诉Monkey主线程可以干什么
                    }
                    if (mRequestAppCrashBugreport){ //需要上报app崩溃的bugreport
                        getBugreport("app_crash" + mReportProcessName + "_"); //生成app_crash文件，同样在子进程中进行
                        mRequestAppCrashBugreport = false;
                    }
                    if (mRequestPeriodicBugreport){ //需要上报什么?这个蒙了
                        getBugreport("Bugreport_"); //单纯的调用bugreport，卧槽，闹半天，每份报告都是单纯的bugreport啊
                        mRequestPeriodicBugreport = false;
                    }
                    if (mRequestDumpsysMemInfo) { //是否需要请求系统内存信息
                        mRequestDumpsysMemInfo = false;
                        shouldReportDumpsysMemInfo = true; //标记应该上报内存信息
                    }
                    if (mMonitorNativeCrashes) { //是否需要监控native的崩溃信息
                        // first time through, when eventCounter == 0, just set up
                        // the watcher (ignore the error)
                        if (checkNativeCrashes() && (eventCounter > 0)) { //发现本地崩溃，且事件数量大于0（这里没有系统服务的回调，而是一直检测文件）
                            Logger.out.println("** New native crash detected."); //在标准输出流，打印natvie崩溃找到
                            if (mRequestBugreport) { //同样调用bugreport
                                getBugreport("native_crash_"); //只不过文件名是这个……，这里子进程中进行
                            }
                            mAbort = mAbort || !mIgnoreNativeCrashes || mKillProcessAfterError; //检查是否需要中断monkey进程，有一个值为true，即会赋值给mAbort
                                              //mAbort、mIgnoreNativeCrashes、mKillProcessAfterError
                        }
                    }
                    if (mAbort) { //如果需要中断，
                        shouldAbort = true; //赋值应该中断
                    }
                    if (mWatchdogWaiting) { //如果已经通知watchdog等待
                        mWatchdogWaiting = false;
                        notifyAll();  //通知所有停留再Monkey对象上的线程，继续运行
                    }
                }

                // Report ANR, dumpsys after releasing lock on this.
                // This ensures the availability of the lock to Activity controller's appNotResponding
                if (shouldReportAnrTraces) { //如果必须上报ANR Trace
                    shouldReportAnrTraces = false;
                    reportAnrTraces(); //通过此方法，上报AnrTraces
                }

                if (shouldReportDumpsysMemInfo) {
                    shouldReportDumpsysMemInfo = false;
                    reportDumpsysMemInfo(); //报告内存信息，使用的命令是：dumpsys meminfo
                }

                if (shouldAbort) { //应该中断monkey进程的处理
                    shouldAbort = false;
                    Logger.out.println("** Monkey aborted due to error."); //标准错误流输出Monkey中断的错误
                    Logger.out.println("Events injected: " + eventCounter); //输出事件数量
                    return eventCounter; //返回事件数量
                }

                // In this debugging mode, we never send any events. This is
                // primarily here so you can manually test the package or category
                // limits, while manually exercising the system.
                if (mSendNoEvents) { //用于测试事件数的？
                    eventCounter++;
                    cycleCounter++;
                    continue;
                }

                if ((mVerbose > 0) && (eventCounter % 100) == 0 && eventCounter != 0) {
                    String calendarTime = MonkeyUtils.toCalendarTime(System.currentTimeMillis()); //计算花费的时间
                    long systemUpTime = SystemClock.elapsedRealtime();
                    Logger.out.println("    //[calendar_time:" + calendarTime + " system_uptime:"
                            + systemUpTime + "]"); //输出花费的时间，以及系统启动的时间？
                    Logger.out.println("    // Sending event #" + eventCounter); //输出事件总数
                }

                MonkeyEvent ev = mEventSource.getNextEvent(); //从EventSource中提取事件，从命令行执行时，实际是从MonkeySourceRandom的getNextEvent（）方法中提取事件的
                if (ev != null) { //如果提取到事件……
                    int injectCode = ev.injectEvent(mWm, mAm, mVerbose); //回调每个MonkeyEvent的injectEvent（）方法，并且把WMS、AMS、还有日志等级都传了进去，具体的操作，由具体的事件对象自己执行，注入码表示成功或者失败
                    if (injectCode == MonkeyEvent.INJECT_FAIL) { //处理失败的情况，卧槽还要+1
                        Logger.out.println("    // Injection Failed");
                        if (ev instanceof MonkeyKeyEvent) { //若事件为MonkeyKeyEvent对象
                            mDroppedKeyEvents++; //则丢弃的事件增加1
                        } else if (ev instanceof MonkeyMotionEvent) { //若事件为MonkeyMotionEvent
                            mDroppedPointerEvents++;  //则丢弃的mDroppedPointerEvents事件增加1
                        } else if (ev instanceof MonkeyFlipEvent) {
                            mDroppedFlipEvents++;
                        } else if (ev instanceof MonkeyRotationEvent) {
                            mDroppedRotationEvents++;
                        }
                    } else if (injectCode == MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION) { //注入事件时，发生错误
                        systemCrashed = true; //说明操作系统发生崩溃，可能SystemServer进程重启
                        Logger.err.println("** Error: RemoteException while injecting event."); //此时标准错误流输出一条，注入事件的事件的时候，远程服务发生错误
                    } else if (injectCode == MonkeyEvent.INJECT_ERROR_SECURITY_EXCEPTION) {  //这是因为安全问题，未注入事件
                        systemCrashed = !mIgnoreSecurityExceptions; //如果命令行参数设置忽略参数，则不算系统出现错误
                        if (systemCrashed) {
                            Logger.err.println("** Error: SecurityException while injecting event."); //标准错误流输出日志，表明注入事件时，发生系统安全错误
                        }
                    }

                    // Don't count throttling as an event.
                    if (!(ev instanceof MonkeyThrottleEvent)) { //只有不是MonkeyThrottleEvent事件对象时，才计算总的次数，完美的将间隔事件忽略掉了
                        eventCounter++;
                        if (mCountEvents) {
                            cycleCounter++;
                        }
                    }
                } else { //这是从双向链表中，没有提取到事件对象的情况
                    if (!mCountEvents) { //如果不需要计算事件数量
                        cycleCounter++; //循环次数增加1
                        writeScriptLog(cycleCounter); //把数量写入脚本文件
                        //Capture the bugreport after n iteration
                        if (mGetPeriodicBugreport) { //这是处理呢
                            if ((cycleCounter % mBugreportFrequency) == 0) {
                                mRequestPeriodicBugreport = true;
                            }
                        }
                    } else { //需要计算的时候，啥也不干……，中断循环完事
                        // Event Source has signaled that we have no more events to process
                        break;
                    }
                }
                //每完整的循环执行一次，Monkey对象锁会释放掉，不然别人哪有机会……
            }
        } catch (RuntimeException e) {
            Logger.error("** Error: A RuntimeException occurred:", e); //捕获到运行时异常，直接标准错误流流输出结果了
        }
        Logger.out.println("Events injected: " + eventCounter); //当系统出现错误，或者事件数量到了，标准输出流中输出事件数
        return eventCounter; //返回注入的事件数
    }

    /**
     * Send SIGNAL_USR1 to all processes. This will generate large (5mb)
     * profiling reports in data/misc, so use with care.
     * 发送一个信号，给所有进程，报告会存放到data/misc目录下
     */
    private void signalPersistentProcesses() {
        try {
            mAm.signalPersistentProcesses(Process.SIGNAL_USR1);

            synchronized (this) {
                wait(2000);
            }
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with activity manager!");
        } catch (InterruptedException e) {
        }
    }

    /**
     * Watch for appearance of new tombstone files, which indicate native
     * crashes.
     * 检查native崩溃的方法，同样检查的是tobstones文件数量
     * @return Returns true if new files have appeared in the list
     */
    private boolean checkNativeCrashes() {
        String[] tombstones = TOMBSTONES_PATH.list(); //检查这个/data/tombstones目录的文件数

        // shortcut path for usually empty directory, so we don't waste even
        // more objects
        if (tombstones == null || tombstones.length == 0) {
            mTombstones = null;
            return false; //说明没有native崩溃
        }

        boolean result = false;

        // use set logic to look for new files
        HashSet<Long> newStones = new HashSet<Long>(); //创建一个Set对象
        for (String t : tombstones) { //遍历所有的tombstones文件名
            if (t.startsWith(TOMBSTONE_PREFIX)) { //如果前缀是tombstone_开头的文件
                File f = new File(TOMBSTONES_PATH, t);
                newStones.add(f.lastModified()); //把文件最后修改的时间，添加到Set集合中
                if (mTombstones == null || !mTombstones.contains(f.lastModified())) { //要是mTombstones还没有赋值，文件的修改时间也没有临时添加到临时创建的Set中
                    result = true; //先把结果赋值为true，说明发现native崩溃
                    waitForTombstoneToBeWritten(Paths.get(TOMBSTONES_PATH.getPath(), t)); //这里貌似没啥用啊……
                    Logger.out.println("** New tombstone found: " + f.getAbsolutePath()
                                       + ", size: " + f.length()); //打印找到的tombstone文件，以及字节数
                }
            }
        }

        // keep the new list for the next time
        mTombstones = newStones; //将临时创建的Set对象，赋值给Monkey对象持有的Set对象负责持有

        return result; //返回是否发生native崩溃的结果
    }

    /**
     * Wait for the given tombstone file to be completely written.
     *
     * @param path The path of the tombstone file.
     */
    private void waitForTombstoneToBeWritten(Path path) {
        boolean isWritten = false;
        try {
            // Ensure file is done writing by sleeping and comparing the previous and current size
            for (int i = 0; i < NUM_READ_TOMBSTONE_RETRIES; i++) {
                long size = Files.size(path); //获取文件的行数？，不对，原来是字节数
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) { }
                if (size > 0 && Files.size(path) == size) {
                    //File size is bigger than 0 and hasn't changed
                    isWritten = true;
                    break;
                }
            }
        } catch (IOException e) {
            Logger.err.println("Failed to get tombstone file size: " + e.toString());
        }
        if (!isWritten) {
            Logger.err.println("Incomplete tombstone file.");
            return;
        }
    }

    /**
     * Return the next command line option. This has a number of special cases
     * which closely, but not exactly, follow the POSIX command line options
     * patterns:
     *
     * <pre>
     * -- means to stop processing additional options
     * -z means option z
     * -z ARGS means option z with (non-optional) arguments ARGS
     * -zARGS means option z with (optional) arguments ARGS
     * --zz means option zz
     * --zz ARGS means option zz with (non-optional) arguments ARGS
     * </pre>
     *
     * Note that you cannot combine single letter options; -abc != -a -b -c
     *
     * @return Returns the option string, or null if there are no more options.
     */
    private String nextOption() {
        if (mNextArg >= mArgs.length) { //检查下标是否大于等于命令行参数的总长度
            return null; //返回null，说明没有参数了
        }
        String arg = mArgs[mNextArg]; //获取指定的一个命令行参数
        if (!arg.startsWith("-")) { //如果参数不是以-开头的，则返回null
            return null;
        }
        mNextArg++;  //下标值+1
        if (arg.equals("--")) { //如果命令行参数的等于--
            return null; //返回null
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') { //单个命令行参数的长度大于1、且第二个字符不等于-时
            if (arg.length() > 2) { //单个命令行参数大于2时
                mCurArgData = arg.substring(2); //截取前两个字符，并赋值给mCurArgData
                return arg.substring(0, 2); //返回前两个字符……
            } else { //单个命令行参数小于等于2时
                mCurArgData = null;
                return arg; //直接返回单个命令行参数
            }
        }
        mCurArgData = null;
        Logger.err.println("arg=\"" + arg + "\" mCurArgData=\"" + mCurArgData + "\" mNextArg="
                + mNextArg + " argwas=\"" + mArgs[mNextArg-1] + "\"" + " nextarg=\"" +
                mArgs[mNextArg] + "\""); //arg="--throttle" mCurArgData="null" mNextArg=1 argwas="--throttle" nextarg="500"
        return arg;
    }

    /**
     * Return the next data associated with the current option.
     *
     * @return Returns the data string, or null of there are no more arguments.
     */
    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        Logger.err.println("data=\"" + data + "\""); //data="500"
        mNextArg++;
        return data;
    }

    /**
     * Returns a long converted from the next data argument, with error handling
     * if not available.
     *
     * @param opt The name of the option.
     * @return Returns a long converted from the argument.
     */
    private long nextOptionLong(final String opt) {
        long result;
        try {
            result = Long.parseLong(nextOptionData()); //费心了，转成long，如果不能转换
        } catch (NumberFormatException e) { //这里捕获不能转换为数字的异常（说明字符串不是数字）
            Logger.err.println("** Error: " + opt + " is not a number");
            throw e;
        }
        return result;
    }

    /**
     * Return the next argument on the command line.
     *
     * @return Returns the argument string, or null if we have reached the end.
     */
    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    /**
     * Print how to use this command.
     * 告知用户怎么使用monkey命令行
     */
    private void showUsage() {
        StringBuffer usage = new StringBuffer();
        usage.append("usage: monkey [-p ALLOWED_PACKAGE [-p ALLOWED_PACKAGE] ...]\n");
        usage.append("              [-c MAIN_CATEGORY [-c MAIN_CATEGORY] ...]\n");
        usage.append("              [--ignore-crashes] [--ignore-timeouts]\n");
        usage.append("              [--ignore-security-exceptions]\n");
        usage.append("              [--monitor-native-crashes] [--ignore-native-crashes]\n");
        usage.append("              [--kill-process-after-error] [--hprof]\n");
        usage.append("              [--match-description TEXT]\n");
        usage.append("              [--pct-touch PERCENT] [--pct-motion PERCENT]\n");
        usage.append("              [--pct-trackball PERCENT] [--pct-syskeys PERCENT]\n");
        usage.append("              [--pct-nav PERCENT] [--pct-majornav PERCENT]\n");
        usage.append("              [--pct-appswitch PERCENT] [--pct-flip PERCENT]\n");
        usage.append("              [--pct-anyevent PERCENT] [--pct-pinchzoom PERCENT]\n");
        usage.append("              [--pct-permission PERCENT]\n");
        usage.append("              [--pkg-blacklist-file PACKAGE_BLACKLIST_FILE]\n");
        usage.append("              [--pkg-whitelist-file PACKAGE_WHITELIST_FILE]\n");
        usage.append("              [--wait-dbg] [--dbg-no-events]\n");
        usage.append("              [--setup scriptfile] [-f scriptfile [-f scriptfile] ...]\n");
        usage.append("              [--port port]\n");
        usage.append("              [-s SEED] [-v [-v] ...]\n");
        usage.append("              [--throttle MILLISEC] [--randomize-throttle]\n");
        usage.append("              [--profile-wait MILLISEC]\n");
        usage.append("              [--device-sleep-time MILLISEC]\n");
        usage.append("              [--randomize-script]\n");
        usage.append("              [--script-log]\n");
        usage.append("              [--bugreport]\n");
        usage.append("              [--periodic-bugreport]\n");
        usage.append("              [--permission-target-system]\n");
        usage.append("              COUNT\n");
        Logger.err.println(usage.toString());
    }
}
