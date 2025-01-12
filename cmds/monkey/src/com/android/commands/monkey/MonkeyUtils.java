/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Misc utilities.
 * 第一次见工具类使用abstract修饰，拽，牛逼（当时有大佬不建议这么使用）
 */
public abstract class MonkeyUtils {

    private static final java.util.Date DATE = new java.util.Date(); //MonkeyUtils类持有的Date对象
    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS "); //MonkeyUtils类持有的SimpleDateFormat对象
    private static PackageFilter sFilter; //MonkeyUtils类持有的PackageFilter对象

    /**
     * 私有构造方法，禁止通过new方式创建对象
     */
    private MonkeyUtils() {
    }

    /**
     * Return calendar time in pretty string.
     * 返回一个格式化后的字符串时间
     * 同步方法，只有获取到MonkeyUtils的Class对象的线程，才能执行该方法
     * 共享变量为DATE、DATE_FORMATTER
     */
    public static synchronized String toCalendarTime(long time) {
        DATE.setTime(time);
        return DATE_FORMATTER.format(DATE);
    }

    /**
     * 获取创建的PackageFilter对象
     * @return
     */
    public static PackageFilter getPackageFilter() {
        if (sFilter == null) {
            sFilter = new PackageFilter();
        }
        return sFilter;
    }

    /**
     * 静态内部类，创建的PackageFilter对象持有两个Set对象，表示用于过滤包级应用
     * 一个set保存有效的包名
     * 另一个set保存无效的包名
     */
    public static class PackageFilter {
        private Set<String> mValidPackages = new HashSet<>(); //PackageFilter对象持有一个用于保存有效包名的Set对象
        private Set<String> mInvalidPackages = new HashSet<>(); //PackageFilter对象持有一个用于保存无效包名的Set对象

        /**
         * 私有构造方法，不允许通过构造方法创建对象，但是同一个类中的外部类可以，牛逼！思路清晰,所谓只在一个类中使用的静态内部类……经典
         */
        private PackageFilter() {
        }

        /**
         * 将某个Set对象中持有的所有元素，全部添加到mValidPackages集合中，相当于交给PackageFilter对象持有（间接持有）
         * @param validPackages Set对象
         */
        public void addValidPackages(Set<String> validPackages) {
            mValidPackages.addAll(validPackages);
        }

        /**
         * 将某个Set对象中持有的所有元素，全部添加到mInvalidPackages集合中，相当于交给PackageFilter对象持有（间接持有）
         * @param invalidPackages
         */
        public void addInvalidPackages(Set<String> invalidPackages) {
            mInvalidPackages.addAll(invalidPackages);
        }

        /**
         * 当Set中持有的元素大于0时，说明存储着有效的包名
         * @return 返回是否存在有效包
         */
        public boolean hasValidPackages() {
            return mValidPackages.size() > 0;
        }

        /**
         * 检查是否包含某个有效包
         * @param pkg 包名
         * @return 是否包含某个有效包
         */
        public boolean isPackageValid(String pkg) {
            return mValidPackages.contains(pkg);
        }

        /**
         * 检查包是否为无效的
         * @param pkg 包名
         * @return true表示无效包
         */
        public boolean isPackageInvalid(String pkg) {
            return mInvalidPackages.contains(pkg);
        }

        /**
         * Check whether we should run against the given package.
         * 用于检查App是否允许启动，通过包名检查，该值会间接反馈到AMS那里
         * @param pkg The package name. 应用的包名
         * @return Returns true if we should run against pkg. true，表示可以允许启动
         */
        public boolean checkEnteringPackage(String pkg) {
            if (mInvalidPackages.size() > 0) { //如果设置了不允许启动的应用，就先在无效包名的集合中是否包含传入的包名
                if (mInvalidPackages.contains(pkg)) {
                    return false;  //如果在无效包的集合中包含此包名，直接返回false，表示应用不能启动
                }
            } else if (mValidPackages.size() > 0) {  //如果没有设置无效的包名，直接检查有效包名（允许启动）的集合
                if (!mValidPackages.contains(pkg)) { //如果在允许启动的包名的集合中没有包含对应的应用
                    return false; //表示在允许启动的集合中没有对应的包名
                }
            }
            return true; //在无效与有效的集合里，都没有设置过的包，就是允许启动的App
        }

        /**
         * 遍历两个set中的包名，并输出日志
         * 1、有效包名
         * 2、无效包名
         * 调试用的……
         */
        public void dump() {
            if (mValidPackages.size() > 0) {
                Iterator<String> it = mValidPackages.iterator(); //获取Set对象的迭代器对象
                while (it.hasNext()) { //检查是否有元素没有遍历
                    Logger.out.println(":AllowPackage: " + it.next()); //向标准输出流输出日志
                }
            }
            if (mInvalidPackages.size() > 0) {
                Iterator<String> it = mInvalidPackages.iterator(); //获取无效包名Set对象的迭代器对象
                while (it.hasNext()) { //如果存在元素
                    Logger.out.println(":DisallowPackage: " + it.next()); //输出日志
                }
            }
        }
    }
}
