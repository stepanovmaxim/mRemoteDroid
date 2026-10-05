package com.freerdp.freerdpcore.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionIntents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foreground service held while an RDP session is connected.
 *
 * Without it, once the screen turns off or the app goes to the background, some
 * vendors (Samsung in particular) cut the app's network within seconds: FreeRDP
 * then fails with "BIO_read ... error 103: Software caused connection abort" and
 * the session drops. A foreground service keeps the process un-frozen with
 * network access; partial wake + Wi-Fi locks keep the session thread and the
 * radio awake so the server's keep-alives are answered.
 */
public class SessionKeepAliveService extends Service
{
	private static final String TAG = "SessionKeepAlive";
	private static final String CHANNEL_ID = "rdp_session";
	private static final int NOTIFICATION_ID = 4242;

	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;

	/** Connected sessions: instance -> {title, document tag}. */
	private static final Map<Long, String[]> sessions = new LinkedHashMap<>();

	/** Registers a connected session; the first one starts the foreground service. */
	public static void start(Context context, long instance, String title, String tag)
	{
		synchronized (sessions)
		{
			sessions.put(instance, new String[] { title != null ? title : "", tag });
		}
		Intent i = new Intent(context, SessionKeepAliveService.class);
		try
		{
			context.startService(i); // the app is in the foreground when a session connects
		}
		catch (Exception e)
		{
			Log.w(TAG, "could not start keep-alive service", e);
		}
	}

	/** Unregisters a session; the service stops with the last one. */
	public static void stop(Context context, long instance)
	{
		boolean empty;
		synchronized (sessions)
		{
			if (sessions.remove(instance) == null)
				return;
			empty = sessions.isEmpty();
		}
		Intent i = new Intent(context, SessionKeepAliveService.class);
		if (empty)
			context.stopService(i);
		else
		{
			try
			{
				context.startService(i); // refresh the notification
			}
			catch (Exception e)
			{
				Log.w(TAG, "could not update keep-alive service", e);
			}
		}
	}

	@Override public int onStartCommand(Intent intent, int flags, int startId)
	{
		Notification notification = buildNotification();
		if (notification == null)
		{
			stopSelf();
			return START_NOT_STICKY;
		}
		try
		{
			if (Build.VERSION.SDK_INT >= 34)
				startForeground(NOTIFICATION_ID, notification,
				                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			else
				startForeground(NOTIFICATION_ID, notification);
		}
		catch (Exception e)
		{
			Log.w(TAG, "startForeground failed", e);
			stopSelf();
			return START_NOT_STICKY;
		}
		acquireLocks();
		return START_NOT_STICKY;
	}

	private Notification buildNotification()
	{
		List<String[]> list;
		synchronized (sessions)
		{
			list = new ArrayList<>(sessions.values());
		}
		if (list.isEmpty())
			return null;

		NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null)
		{
			NotificationChannel ch =
			    new NotificationChannel(CHANNEL_ID, getString(R.string.session_notification_channel),
			                            NotificationManager.IMPORTANCE_LOW);
			ch.setShowBadge(false);
			nm.createNotificationChannel(ch);
		}

		// One session: tapping returns straight to it. Several: to the connection list,
		// where the open ones are marked.
		Intent open;
		String title;
		String text;
		if (list.size() == 1)
		{
			title = getString(R.string.session_notification_title, list.get(0)[0]);
			text = getString(R.string.session_notification_text);
			open = SessionIntents.reopen(this, list.get(0)[1]);
		}
		else
		{
			title = getString(R.string.session_notification_title_many, list.size());
			StringBuilder names = new StringBuilder();
			for (String[] item : list)
			{
				if (names.length() > 0)
					names.append(", ");
				names.append(item[0]);
			}
			text = names.toString();
			open = null;
		}
		if (open == null)
		{
			open = getPackageManager().getLaunchIntentForPackage(getPackageName());
			if (open != null)
				open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
				              Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		}
		PendingIntent content = null;
		if (open != null)
			content = PendingIntent.getActivity(this, 0, open,
			                                    PendingIntent.FLAG_IMMUTABLE |
			                                        PendingIntent.FLAG_UPDATE_CURRENT);

		return new NotificationCompat.Builder(this, CHANNEL_ID)
		    .setSmallIcon(R.drawable.ic_stat_session)
		    .setContentTitle(title)
		    .setContentText(text)
		    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
		    .setContentIntent(content)
		    .setOngoing(true)
		    .setOnlyAlertOnce(true)
		    .setCategory(NotificationCompat.CATEGORY_SERVICE)
		    .setPriority(NotificationCompat.PRIORITY_LOW)
		    .build();
	}

	@SuppressWarnings("deprecation")
	private void acquireLocks()
	{
		if (wakeLock == null)
		{
			PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mRemoteDroid:rdp-session");
			wakeLock.setReferenceCounted(false);
			wakeLock.acquire();
		}
		if (wifiLock == null)
		{
			WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
			if (wm != null)
			{
				wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
				                             "mRemoteDroid:rdp-session");
				wifiLock.setReferenceCounted(false);
				wifiLock.acquire();
			}
		}
	}

	@Override public void onDestroy()
	{
		if (wakeLock != null && wakeLock.isHeld())
			wakeLock.release();
		if (wifiLock != null && wifiLock.isHeld())
			wifiLock.release();
		wakeLock = null;
		wifiLock = null;
		super.onDestroy();
	}

	@Nullable @Override public IBinder onBind(Intent intent)
	{
		return null;
	}
}
