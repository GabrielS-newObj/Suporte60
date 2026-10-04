package com.florian.suporte60;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.florian.suporte60.BuildConfig;


@SuppressLint("CustomSplashScreen") //for minSdk >= 30
public class InitialSplashScreen extends ALayoutActivity {

    private final ExecutorService task = Executors.newSingleThreadExecutor();
    private final long delay = 3000;
    private boolean isDataLoading = true;
    private final Runnable intentTask = () -> {


        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            new Handler(Looper.getMainLooper()).post(()->
                    Toast.makeText(InitialSplashScreen.this, "Tente novamente mais tarde", Toast.LENGTH_SHORT).show()
            );
        }

        isDataLoading = false;

        new Handler(Looper.getMainLooper()).post(() -> {
            // CORREÇÃO: build "server" não mostra mais tela de login — vai
            // direto para a fila de atendimento. A autenticação com o
            // Firebase (exigida pelas regras do banco) agora acontece
            // sozinha, em segundo plano, dentro de AdminChamadosActivity.
            //
            // AdminChamadosActivity só existe no source set "server" (não
            // existe quando este mesmo arquivo, que é compartilhado, é
            // compilado para o flavor "client"). Por isso a classe é
            // resolvida via Class.forName (em runtime) em vez de uma
            // referência direta "AdminChamadosActivity.class", que quebraria
            // a compilação do build client.
            Intent intent;
            if (BuildConfig.FLAVOR.equals("server")) {
                try {
                    Class<?> destino = Class.forName("com.florian.suporte60.AdminChamadosActivity");
                    intent = new Intent(InitialSplashScreen.this, destino);
                } catch (ClassNotFoundException e) {
                    // Não deveria acontecer num build "server" correto.
                    throw new RuntimeException("AdminChamadosActivity não encontrada no build server", e);
                }
            } else {
                intent = new Intent(InitialSplashScreen.this, MainActivity.class);
            }

            startActivity(intent);

            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        });
    };




    @Override
    protected void onCreate(Bundle savedInstanceState) {

        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_initial_splash_screen);

        splashScreen.setKeepOnScreenCondition(()->isDataLoading);


        task.execute(intentTask);

    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (task != null && !task.isShutdown()) {
            task.shutdownNow();
        }
    }


}