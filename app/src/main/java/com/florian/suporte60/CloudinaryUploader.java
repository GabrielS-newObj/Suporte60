package com.florian.suporte60;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CloudinaryUploader {

    public interface UploadCallback {
        /** Chamado na UI thread quando o upload termina com sucesso. */
        void onSuccess(String urlSegura);

        /** Chamado na UI thread quando o upload falha, por qualquer motivo. */
        void onFailure(String mensagemErro);
    }

    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public static void enviarAudio(File arquivoAudio, UploadCallback callback) {
        String cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME;
        String uploadPreset = BuildConfig.CLOUDINARY_UPLOAD_PRESET;

        if (cloudName == null || cloudName.isEmpty() || uploadPreset == null || uploadPreset.isEmpty()) {
            postFailure(callback, "Cloudinary não configurado. Defina CLOUDINARY_CLOUD_NAME e " +
                    "CLOUDINARY_UPLOAD_PRESET no local.properties.");
            return;
        }

        // Cloudinary trata áudio dentro do endpoint "video" (não existe um
        // endpoint separado só para áudio) — é o mesmo caminho que já estava
        // (corretamente) anotado na função morta functions/index.js.
        String url = "https://api.cloudinary.com/v1_1/" + cloudName + "/video/upload";

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart(
                        "file",
                        arquivoAudio.getName(),
                        RequestBody.create(arquivoAudio, MediaType.parse("audio/mp4"))
                )
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postFailure(callback, "Falha de rede: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String corpo = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    // Erros comuns aqui: preset não é "unsigned", cloud name
                    // errado, ou preset não existe.
                    postFailure(callback, "Cloudinary recusou o upload (HTTP " + response.code() + ")");
                    return;
                }

                try {
                    JSONObject json = new JSONObject(corpo);
                    String urlSegura = json.getString("secure_url");
                    mainThreadHandler.post(() -> callback.onSuccess(urlSegura));
                } catch (JSONException e) {
                    postFailure(callback, "Resposta inesperada do Cloudinary.");
                }
            }
        });
    }

    private static void postFailure(UploadCallback callback, String mensagem) {
        mainThreadHandler.post(() -> callback.onFailure(mensagem));
    }
}
