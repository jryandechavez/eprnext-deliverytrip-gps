package com.erpnext.gpstracker;

import android.content.Context;
import java.net.HttpURLConnection;

final class ErpAuth {
    static void apply(Context context,HttpURLConnection connection) {
        String session=Config.sessionId(context);
        if(!session.isEmpty()) connection.setRequestProperty("Cookie","sid="+session);
        else if(!Config.key(context).isEmpty()) connection.setRequestProperty("Authorization","token "+Config.key(context)+":"+Config.secret(context));
    }
}
