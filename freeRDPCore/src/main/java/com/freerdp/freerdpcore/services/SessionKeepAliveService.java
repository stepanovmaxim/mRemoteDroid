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
	private static final String EXTRA_TITLE = "title";

	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;

	public static void start(Context context, String title)
	{
		Intent i = new Intent(context, SessionKeepAliveService.class);
		i.putExtra(EXTRA_TITLE, title);
		try
		{
			context.startService(i); // the app is in the foreground when a session connects
		}
		catch (Exception e)
		{
			Log.w(TAG, "could not start keep-alive service", e);
		}
	}

	public static void stop(Context context)
	{
		context.stopService(new Intent(context, SessionKeepAliveService.class));
	}

	@Override public int onStartCommand(Intent intent, int flags, int startId)
	{
		String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
		Notification notification = buildNotification(title != null ? title : "");
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

	private Notification buildNotification(String title)
	{
		NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null)
		{
			NotificationChannel ch =
			    new NotificationChannel(CHANNEL_ID, getString(R.string.session_notification_channel),
			                            NotificationManager.IMPORTANCE_LOW);
			ch.setShowBadge(false);
			nm.createNotificationChannel(ch);
		}

		// tapping brings the app's task (with the session on top) back to the front
		Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
		PendingIntent content = null;
		if (open != null)
		{
			open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			content = PendingIntent.getActivity(this, 0, open,
			                                    PendingIntent.FLAG_IMMUTABLE |
			                                        PendingIntent.FLAG_UPDATE_CURRENT);
		}

		return new NotificationCompat.Builder(this, CHANNEL_ID)
		    .setSmallIcon(R.drawable.ic_stat_session)
		    .setContentTitle(getString(R.string.session_notification_title, title))
		    .setContentText(getString(R.string.session_notification_text))
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
