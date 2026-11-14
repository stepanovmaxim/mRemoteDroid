package com.freerdp.freerdpcore.presentation;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.freerdp.freerdpcore.application.GlobalApp;

/**
 * Intents for session windows. Every session runs in its own task (document), so
 * open sessions show up as separate cards in Recents. Kept out of SessionActivity so
 * callers don't need AppCompat on their classpath.
 */
public final class SessionIntents
{
	private SessionIntents()
	{
	}

	/** Desktop scale choices in percent; 0 = automatic by screen density. */
	public static final int[] SCALE_OPTIONS = { 0, 100, 125, 150, 175, 200, 250 };

	private static final String PREFS = "session_ui";
	private static final String PREF_SCALE = "desktop_scale";

	/** The desktop scale used for every session, also changed from the session menu. */
	public static int getDesktopScale(Context context)
	{
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREF_SCALE, 0);
	}

	public static void setDesktopScale(Context context, int percent)
	{
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		    .edit()
		    .putInt(PREF_SCALE, percent)
		    .apply();
	}

	/**
	 * Opens a session window for the connection identified by {@code tag} (a non-secret
	 * URI), or brings its existing window to the front.
	 */
	public static Intent reopen(Context context, String tag)
	{
		Intent intent = new Intent(context, SessionActivity.class);
		intent.setData(Uri.parse(tag));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_NEW_TASK);
		return intent;
	}

	/**
	 * Starts a new session. The connection URI carries the password, so it is handed
	 * over in memory: Android keeps document intents in Recents.
	 */
	public static Intent connect(Context context, String tag, Uri connectUri)
	{
		Intent intent = reopen(context, tag);
		intent.putExtra(SessionActivity.PARAM_PENDING_CONNECTION,
		                GlobalApp.putPendingConnection(connectUri));
		return intent;
	}
}
