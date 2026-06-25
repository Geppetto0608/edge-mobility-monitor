package com.example.jetsonbtmonitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

final class UserSession {
    private static final String PREFS_NAME = "wheelchair_user_session";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_PROFILE = "profile";

    private UserSession() {
    }

    static boolean isLoggedIn(Context context) {
        return preferences(context).getBoolean(KEY_LOGGED_IN, false);
    }

    static void saveProfile(Context context, JSONObject profile) {
        if (profile == null) {
            profile = new JSONObject();
        }
        preferences(context)
                .edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_PROFILE, profile.toString())
                .apply();
    }

    static JSONObject profile(Context context) {
        String rawProfile = preferences(context).getString(KEY_PROFILE, "{}");
        try {
            return new JSONObject(rawProfile);
        } catch (JSONException exception) {
            return new JSONObject();
        }
    }

    static String userId(Context context) {
        return profile(context).optString("user_id", "");
    }

    static void logout(Context context) {
        preferences(context).edit().clear().apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
