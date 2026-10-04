package com.florian.suporte60;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

    public class MyFirebaseMessagingService extends FirebaseMessagingService {

        @Override public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
            super.onMessageReceived(remoteMessage); String titulo = getString(R.string.notif_titulo_padrao);
            String corpo = getString(R.string.notif_corpo_padrao);

            if (remoteMessage.getNotification() != null) {

                if (remoteMessage.getNotification().getTitle() != null) titulo = remoteMessage.getNotification().getTitle();

                if (remoteMessage.getNotification().getBody() != null) corpo = remoteMessage.getNotification().getBody();
            }

            String usuarioId = remoteMessage.getData().get("usuarioId");
            dispararNotificacao(titulo, corpo, usuarioId); }


        private void dispararNotificacao(String titulo, String corpo, String usuarioId) {

            String channelId = "canal_suporte_atendente";

            Intent intent;
            try {
                // Tenta encontrar a tela do servidor dinamicamente (para o app do Atendente)
                Class<?> activityClass = Class.forName("com.florian.suporte60.AdminChamadosActivity");
                intent = new Intent(this, activityClass);
            } catch (ClassNotFoundException e) {
                // Se a classe não for encontrada (ou seja, está rodando no app do Cliente),
                // ele redireciona para a tela principal padrão como fallback.
                intent = new Intent(this, MainActivity.class);
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
// ID único por chamado: sem usuarioId (ex.: caso de erro genérico), cai no ID padrão

            int notifyId = (usuarioId != null) ? usuarioId.hashCode() : 101;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, notifyId, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(titulo)
                    .setContentText(corpo)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent);

            NotificationManager manager = (NotificationManager)

                    getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { NotificationChannel channel = new NotificationChannel(channelId, "Suporte Atendente", NotificationManager.IMPORTANCE_HIGH);
                manager.createNotificationChannel(channel);
            }

            manager.notify(notifyId, builder.build());
        }
    }


