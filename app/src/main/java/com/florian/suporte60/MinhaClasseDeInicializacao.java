package com.florian.suporte60;
public class MinhaClasseDeInicializacao {

    public interface OnInitializationCompleteListener {
        void onComplete();
    }

    public void iniciar(OnInitializationCompleteListener listener) {
        new Thread(() -> {
            try {
                carregarModulos();
                verificarSessaoUsuario();

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (listener != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(listener::onComplete);
                }
            }
        }).start();
    }

    private void carregarModulos() {
    }

    private void verificarSessaoUsuario() {
    }
}

