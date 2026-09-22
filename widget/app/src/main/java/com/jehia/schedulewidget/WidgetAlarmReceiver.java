package com.jehia.schedulewidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 收到翻页闹钟：刷一次小组件，再把下一次排上。 */
public class WidgetAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // updateAll 里会重新排闹钟，所以这里不用再排一次
        WidgetCommon.updateAll(context.getApplicationContext());
    }
}