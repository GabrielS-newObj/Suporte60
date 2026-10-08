package com.florian.suporte60;

import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class AssistantIA {

    private static final String TAG = "AssistantIA";
    private static final String[] MODELOS = {
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3-flash-preview",
            "gemini-3.7-flash"
    };
    private static final long ESPERA_ENTRE_TENTATIVAS_MS = 1500;

    private static final int RODADAS_EXTRAS = 2;
    private static final long ESPERA_ENTRE_RODADAS_MS = 40_000;

    private static final ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor();


    private static final String MARCA_ATENDENTE = "[ATENDENTE]";
    private static final Pattern PADRAO_MARCA_ATENDENTE =
            Pattern.compile("\\[\\s*ATENDENTE\\s*\\]", Pattern.CASE_INSENSITIVE);

    private static final String RESPOSTA_PEDIDO_ATENDENTE_PADRAO =
            "Entendo. Posso tentar te ajudar por aqui primeiro. " +
                    "Se ainda quiser falar com um atendente, é só me pedir de novo.";
    private static final String MENSAGEM_TENTANDO_NOVAMENTE =
            "Estou com instabilidade agora, mas vou tentar de novo automaticamente. " +
                    "Aguarde um instante, por favor.";
    private static final String MENSAGEM_FALHA_TEMPORARIA =
            "Não consegui responder mesmo tentando de novo. " +
                    "Pode enviar sua mensagem outra vez daqui a alguns minutos?";
    private static final String RESPOSTA_ENCAMINHANDO_ATENDENTE =
            "Entendi. Vou chamar um atendente para continuar com você. Aguarde um instante.";

    private static final int HORA_INICIO_ATENDIMENTO = 7;
    private static final int HORA_FIM_ATENDIMENTO = 20;
    private static final TimeZone FUSO_BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final String MENSAGEM_FORA_HORARIO =
            "No momento estamos fora do nosso horário de atendimento, que é das 7h às 20h. " +
                    "Por favor, volte dentro desse período que teremos prazer em te ajudar!";

    private static String montarInstrucoes(boolean audio, boolean pedidoPendente) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o assistente virtual de suporte do app Suporte 60+, que ajuda ")
                .append("pessoas idosas a usar o celular. Responda em português, com frases ")
                .append("curtas, simples, claras e pacientes, diretamente ao cliente.\n\n");

        if (audio) {
            sb.append("A mensagem do cliente é o ÁUDIO anexado. Ouça e responda ao que foi ")
                    .append("pedido ou perguntado, como responderia a uma mensagem de texto. ")
                    .append("Não diga frases como 'ouvi seu áudio' nem descreva o áudio. ")
                    .append("Se o áudio estiver incompreensível ou sem fala, peça educadamente ")
                    .append("para o cliente reenviar a dúvida por texto.\n\n");
        }

        sb.append("REGRA DO ATENDENTE HUMANO: se o cliente pedir claramente para falar com um ")
                .append("atendente, uma pessoa ou um humano (ou disser que não quer falar com a ")
                .append("IA/robô), comece a resposta com a marcação ").append(MARCA_ATENDENTE)
                .append(" e, depois dela, escreva uma resposta curta e educada dizendo que você ")
                .append("vai tentar ajudar por aqui e que, se ele ainda quiser um atendente, é só ")
                .append("pedir de novo. NÃO diga que vai chamar ou transferir para um atendente. ")
                .append("Se o cliente apenas estiver confuso, com dificuldade ou reclamando, sem ")
                .append("pedir uma pessoa, NÃO use a marcação: tente ajudar. Nunca use a marcação ")
                .append("em nenhuma outra situação e nunca a mencione ao cliente.");

        if (pedidoPendente) {
            sb.append("\n\nCONTEXTO: na mensagem anterior o cliente já pediu um atendente e você ")
                    .append("ofereceu continuar ajudando. Se esta mensagem confirmar ou repetir ")
                    .append("esse pedido (por exemplo 'sim', 'quero', 'quero falar com uma pessoa', ")
                    .append("'por favor, um atendente'), comece a resposta com ").append(MARCA_ATENDENTE)
                    .append(". Se ele passou a fazer outra pergunta, responda normalmente, sem ")
                    .append("a marcação.");
        }
        return sb.toString();
    }

    private static final long TAMANHO_MAXIMO_AUDIO_BYTES = 15L * 1024 * 1024;

    private final DatabaseReference databaseRef;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    private final OkHttpClient geminiClient = httpClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    public AssistantIA() {
        this.databaseRef = FirebaseDatabase.getInstance().getReference();
    }

    private static final int LIMITE_RESPOSTAS_IA = 20;

    public static final long LIMITE_ESPERA_CLIENTE_MS = 30 * 60 * 1000L;

    public static final long ATRASO_LIMPEZA_HISTORICO_MS = 10 * 1000L;

    public void encerrarSessaoPorInatividade(String uid, String textoEncerramento) {
        final AtomicBoolean encerrar = new AtomicBoolean(false);
        controleRef(uid).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData dados) {
                encerrar.set(false);
                String sessao = dados.child("sessao").getValue(String.class);
                boolean escalado = Boolean.TRUE.equals(dados.child("escalado").getValue(Boolean.class));
                Long ultima = dados.child("ultimaInteracao").getValue(Long.class);

                boolean semSessao = sessao == null || sessao.isEmpty() || ultima == null;
                boolean aindaNoPrazo = ultima != null
                        && System.currentTimeMillis() - ultima < LIMITE_ESPERA_CLIENTE_MS;

                if (escalado || semSessao || aindaNoPrazo) {
                    return Transaction.success(dados);
                }
                dados.setValue(null);
                encerrar.set(true);
                return Transaction.success(dados);
            }

            @Override
            public void onComplete(@Nullable DatabaseError erro, boolean confirmada, @Nullable DataSnapshot snapshot) {
                if (erro != null || !confirmada) {
                    Log.w(TAG, "Encerramento por inatividade não confirmado para " + uid
                            + (erro != null ? ": " + erro.getMessage() : ""));
                    return;
                }
                if (encerrar.get()) {
                    concluirEncerramentoPorInatividade(uid, textoEncerramento);
                }
            }
        });
    }

    private void concluirEncerramentoPorInatividade(String uid, String textoEncerramento) {
        Map<String, Object> atualizacoes = new HashMap<>();
        atualizacoes.put("chamados/" + uid, null);

        String chave = databaseRef.child("conversas").child(uid).push().getKey();
        if (chave != null) {
            Map<String, Object> aviso = new HashMap<>();
            aviso.put("texto", textoEncerramento);
            aviso.put("enviadaPeloAtendente", true);
            aviso.put("remetente", "ia");
            aviso.put("timestamp", System.currentTimeMillis());
            atualizacoes.put("conversas/" + uid + "/" + chave, aviso);
        }
        databaseRef.updateChildren(atualizacoes)
                .addOnFailureListener(e -> Log.e(TAG,
                        "Falha ao concluir o encerramento por inatividade de " + uid, e));

        databaseRef.child("atendimentos_pendentes").child(uid).removeValue()
                .addOnFailureListener(e -> Log.w(TAG,
                        "Não foi possível limpar atendimentos_pendentes/" + uid, e));
    }

    public void processar(String uid, String mensagemUsuario) {
        prepararChamada(uid, (chaveApi, sessao, pedidoPendente) ->
                tentarTexto(chaveApi, uid, sessao, mensagemUsuario, pedidoPendente, 0));
    }

    private void tentarTexto(String chaveApi, String uid, String sessao,
                             String mensagemUsuario, boolean pedidoPendente, int rodada) {
        try {
            chamarGemini(chaveApi, montarPartsTexto(mensagemUsuario, pedidoPendente), new GeminiCallback() {
                @Override
                public void onSuccess(String respostaIA) {
                    finalizarComSucesso(uid, sessao, respostaIA);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Erro na chamada à Gemini API (texto, rodada " + (rodada + 1) + "): " + e.getMessage());
                    aoFalharRodada(uid, sessao, rodada, () ->
                            tentarTexto(chaveApi, uid, sessao, mensagemUsuario, pedidoPendente, rodada + 1));
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Falha ao montar requisição de texto para a IA: " + e.getMessage(), e);
            finalizarComFalha(uid, sessao);
        }
    }

    public void processarAudio(String uid, String urlAudio) {
        if (urlAudio == null || urlAudio.trim().isEmpty()) return;

        prepararChamada(uid, (chaveApi, sessao, pedidoPendente) ->
                baixarAudioComoBase64(urlAudio, new AudioDownloadCallback() {
                    @Override
                    public void onSuccess(String base64Audio, String mimeType) {
                        tentarAudio(chaveApi, uid, sessao, base64Audio, mimeType, pedidoPendente, 0);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "Falha ao baixar/preparar áudio para a IA: " + e.getMessage(), e);
                        finalizarComFalha(uid, sessao);
                    }
                })
        );
    }

    private void tentarAudio(String chaveApi, String uid, String sessao, String base64Audio,
                             String mimeType, boolean pedidoPendente, int rodada) {
        try {
            chamarGemini(chaveApi, montarPartsAudio(base64Audio, mimeType, pedidoPendente), new GeminiCallback() {
                @Override
                public void onSuccess(String respostaIA) {
                    finalizarComSucesso(uid, sessao, respostaIA);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Erro na chamada à Gemini API (áudio, rodada " + (rodada + 1) + "): " + e.getMessage());
                    aoFalharRodada(uid, sessao, rodada, () ->
                            tentarAudio(chaveApi, uid, sessao, base64Audio, mimeType, pedidoPendente, rodada + 1));
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Falha ao montar requisição de áudio para a IA: " + e.getMessage(), e);
            finalizarComFalha(uid, sessao);
        }
    }

    private void aoFalharRodada(String uid, String sessao, int rodada, Runnable novaRodada) {
        if (rodada >= RODADAS_EXTRAS) {
            finalizarComFalha(uid, sessao);
            return;
        }
        controleRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String atual = snapshot.child("sessao").getValue(String.class);
                boolean escalado = Boolean.TRUE.equals(snapshot.child("escalado").getValue(Boolean.class));
                if (sessao == null || !sessao.equals(atual) || escalado) {
                    Log.i(TAG, "Nova rodada cancelada: atendimento encerrado ou atendente já assumiu.");
                    return;
                }
                if (rodada == 0) {
                    salvarRespostaNoFirebase(uid, MENSAGEM_TENTANDO_NOVAMENTE);
                }
                Log.i(TAG, "Todos os modelos falharam; nova rodada em "
                        + (ESPERA_ENTRE_RODADAS_MS / 1000) + " s (" + (rodada + 2) + "/" + (RODADAS_EXTRAS + 1) + ").");
                agendador.schedule(novaRodada, ESPERA_ENTRE_RODADAS_MS, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                finalizarComFalha(uid, sessao);
            }
        });
    }

    private enum Decision { REPLY, ESCALATE, SILENCE, OUT_OF_HOURS }

    private enum Destino { PUBLICAR, DESCARTAR, TENTAR_DE_NOVO, ESCALATE }

    private DatabaseReference controleRef(String uid) {
        return databaseRef.child("controle_ia").child(uid);
    }

    private interface HoraCallback {
        void onResultado(boolean dentroDoHorarioComercial);
    }

    private void obterHoraOficialBrasilia(HoraCallback callback) {
        databaseRef.child(".info/serverTimeOffset").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long offsetMs = snapshot.getValue(Long.class);
                long agoraServidorMs = System.currentTimeMillis() + (offsetMs == null ? 0L : offsetMs);

                Calendar agoraBrasilia = Calendar.getInstance(FUSO_BRASILIA);
                agoraBrasilia.setTimeInMillis(agoraServidorMs);
                int hora = agoraBrasilia.get(Calendar.HOUR_OF_DAY);

                boolean dentroDoHorario = hora >= HORA_INICIO_ATENDIMENTO && hora < HORA_FIM_ATENDIMENTO;
                callback.onResultado(dentroDoHorario);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Não foi possível obter o horário oficial (Firebase); seguindo sem checar horário.");
                callback.onResultado(true);
            }
        });
    }

    private void prepararChamada(String uid, ChamadaLiberada acao) {
        final String chaveApi = BuildConfig.GEMINI_API_KEY;

        obterHoraOficialBrasilia(dentroDoHorario ->
                prepararChamadaTransacao(uid, acao, chaveApi, dentroDoHorario));
    }

    private void prepararChamadaTransacao(String uid, ChamadaLiberada acao, String chaveApi,
                                          boolean dentroDoHorario) {
        final AtomicReference<Decision> decisao = new AtomicReference<>(Decision.SILENCE);
        final AtomicReference<String> sessaoRef = new AtomicReference<>(null);
        final AtomicReference<Boolean> pedidoPendenteRef = new AtomicReference<>(false);

        controleRef(uid).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData dados) {
                boolean escalado = Boolean.TRUE.equals(dados.child("escalado").getValue(Boolean.class));
                Long c = dados.child("countIA").getValue(Long.class);
                long contagem = (c == null) ? 0L : c;

                if (escalado) {
                    decisao.set(Decision.SILENCE);
                    return Transaction.success(dados);
                }

                String sessao = dados.child("sessao").getValue(String.class);
                boolean conversaNova = (sessao == null || sessao.isEmpty());

                if (conversaNova && !dentroDoHorario) {
                    decisao.set(Decision.OUT_OF_HOURS);
                    return Transaction.success(dados);
                }

                if (conversaNova) {
                    sessao = UUID.randomUUID().toString();
                    dados.child("sessao").setValue(sessao);
                }
                sessaoRef.set(sessao);
                pedidoPendenteRef.set(Boolean.TRUE.equals(
                        dados.child("pedidoAtendente").getValue(Boolean.class)));

                if (contagem >= LIMITE_RESPOSTAS_IA) {
                    decisao.set(Decision.ESCALATE);
                    dados.child("escalado").setValue(true);
                    return Transaction.success(dados);
                }

                decisao.set(Decision.REPLY);
                dados.child("countIA").setValue(contagem + 1);
                dados.child("ultimaInteracao").setValue(System.currentTimeMillis());
                return Transaction.success(dados);
            }

            @Override
            public void onComplete(@Nullable DatabaseError erro, boolean confirmada, @Nullable DataSnapshot snapshot) {
                if (erro != null || !confirmada) {
                    Log.e(TAG, "Falha ao atualizar controle_ia/" + uid + ": "
                            + (erro != null ? erro.getMessage() : "transação não confirmada"));
                    return;
                }

                switch (decisao.get()) {
                    case SILENCE:
                        Log.i(TAG, "IA em silêncio: controle_ia/" + uid + " está com escalado=true.");
                        return;

                    case OUT_OF_HOURS:
                        salvarRespostaNoFirebase(uid, MENSAGEM_FORA_HORARIO);
                        return;

                    case ESCALATE:
                        salvarRespostaNoFirebase(uid, "Vou chamar um atendente para continuar com você. Aguarde um instante.");
                        databaseRef.child("atendimentos_pendentes").child(uid).setValue(true);
                        return;

                    case REPLY:
                        final String sessao = sessaoRef.get();
                        if (chaveApi == null || chaveApi.trim().isEmpty()) {
                            Log.e(TAG, "GEMINI_API_KEY não configurada no local.properties.");
                            finalizarComFalha(uid, sessao);
                            return;
                        }
                        try {
                            acao.executar(chaveApi, sessao, Boolean.TRUE.equals(pedidoPendenteRef.get()));
                        } catch (JSONException e) {
                            Log.e(TAG, "Falha ao montar requisição para a IA: " + e.getMessage(), e);
                            finalizarComFalha(uid, sessao);
                        }
                        return;
                }
            }
        });
    }

    private void finalizarComSucesso(String uid, String sessao, String respostaIA) {
        final boolean pediuAtendente = respostaIA != null
                && PADRAO_MARCA_ATENDENTE.matcher(respostaIA).find();
        String limpa = respostaIA == null ? ""
                : PADRAO_MARCA_ATENDENTE.matcher(respostaIA).replaceAll("").trim();
        final String textoParaCliente = limpa.isEmpty() && pediuAtendente
                ? RESPOSTA_PEDIDO_ATENDENTE_PADRAO : limpa;

        final AtomicReference<Destino> destino = new AtomicReference<>(Destino.DESCARTAR);

        controleRef(uid).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData dados) {
                String atual = dados.child("sessao").getValue(String.class);
                if (sessao == null || !sessao.equals(atual)) {
                    destino.set(Destino.DESCARTAR);
                    return Transaction.success(dados);
                }

                dados.child("falhasSeguidas").setValue(null);

                if (!pediuAtendente) {
                    dados.child("pedidoAtendente").setValue(null);
                    destino.set(Destino.PUBLICAR);
                    return Transaction.success(dados);
                }

                boolean jaTinhaPedido = Boolean.TRUE.equals(
                        dados.child("pedidoAtendente").getValue(Boolean.class));
                if (jaTinhaPedido) {
                    dados.child("pedidoAtendente").setValue(null);
                    dados.child("escalado").setValue(true);
                    destino.set(Destino.ESCALATE);
                } else {
                    dados.child("pedidoAtendente").setValue(true);
                    destino.set(Destino.PUBLICAR);
                }
                return Transaction.success(dados);
            }

            @Override
            public void onComplete(@Nullable DatabaseError erro, boolean confirmada, @Nullable DataSnapshot snapshot) {
                if (erro != null || !confirmada) {
                    Log.e(TAG, "Falha ao validar sessão em controle_ia/" + uid + ": "
                            + (erro != null ? erro.getMessage() : "transação não confirmada"));
                    return;
                }
                switch (destino.get()) {
                    case PUBLICAR:
                        salvarRespostaNoFirebase(uid, textoParaCliente);
                        return;
                    case ESCALATE:
                        salvarRespostaNoFirebase(uid, RESPOSTA_ENCAMINHANDO_ATENDENTE);
                        databaseRef.child("atendimentos_pendentes").child(uid).setValue(true);
                        return;
                    default:
                        Log.i(TAG, "Resposta da IA descartada: o atendimento foi encerrado enquanto ela era gerada.");
                }
            }
        });
    }

    private void finalizarComFalha(String uid, String sessao) {
        final AtomicReference<Destino> destino = new AtomicReference<>(Destino.DESCARTAR);

        controleRef(uid).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData dados) {
                String atual = dados.child("sessao").getValue(String.class);
                if (sessao == null || !sessao.equals(atual)) {
                    destino.set(Destino.DESCARTAR);
                    return Transaction.success(dados);
                }

                Long c = dados.child("countIA").getValue(Long.class);
                long contagem = (c == null) ? 0L : c;
                dados.child("countIA").setValue(Math.max(0L, contagem - 1));

                dados.child("falhasSeguidas").setValue(null);
                destino.set(Destino.TENTAR_DE_NOVO);
                return Transaction.success(dados);
            }

            @Override
            public void onComplete(@Nullable DatabaseError erro, boolean confirmada, @Nullable DataSnapshot snapshot) {
                if (erro != null || !confirmada) {
                    Log.e(TAG, "Falha ao registrar erro da IA em controle_ia/" + uid + ": "
                            + (erro != null ? erro.getMessage() : "transação não confirmada"));
                    return;
                }
                if (destino.get() == Destino.TENTAR_DE_NOVO) {
                    salvarRespostaNoFirebase(uid, MENSAGEM_FALHA_TEMPORARIA);
                } else {
                    Log.i(TAG, "Falha da IA ignorada: o atendimento foi encerrado antes de ela terminar.");
                }
            }
        });
    }

    private void baixarAudioComoBase64(String urlAudio, AudioDownloadCallback callback) {
        Request request = new Request.Builder().url(urlAudio).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onFailure(new IOException("Falha ao baixar áudio: HTTP " + response.code()));
                        return;
                    }
                    byte[] bytes = response.body().bytes();
                    if (bytes.length == 0) {
                        callback.onFailure(new IOException("Áudio baixado veio vazio."));
                        return;
                    }
                    if (bytes.length > TAMANHO_MAXIMO_AUDIO_BYTES) {
                        callback.onFailure(new IOException("Áudio muito grande para a IA processar (" + bytes.length + " bytes)."));
                        return;
                    }
                    String base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    callback.onSuccess(base64Audio, "audio/aac");
                } catch (IOException e) {
                    callback.onFailure(e);
                } finally {
                    if (response.body() != null) response.close();
                }
            }
        });
    }

    private JSONArray montarPartsTexto(String texto, boolean pedidoPendente) throws JSONException {
        JSONArray partsArray = new JSONArray();
        partsArray.put(new JSONObject().put("text", montarInstrucoes(false, pedidoPendente)));
        partsArray.put(new JSONObject().put("text", "Mensagem do cliente: " + texto));
        return partsArray;
    }

    private JSONArray montarPartsAudio(String base64Audio, String mimeType, boolean pedidoPendente) throws JSONException {
        JSONArray partsArray = new JSONArray();

        JSONObject partTexto = new JSONObject();
        partTexto.put("text", montarInstrucoes(true, pedidoPendente));
        partsArray.put(partTexto);

        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", base64Audio);
        JSONObject partAudio = new JSONObject();
        partAudio.put("inline_data", inlineData);
        partsArray.put(partAudio);

        return partsArray;
    }

    private void chamarGemini(String chaveApi, JSONArray parts, GeminiCallback callback) {
        chamarGemini(chaveApi, parts, callback, 0);
    }

    private static boolean erroTemporario(int codigoHttp) {
        return codigoHttp == 429 || codigoHttp == 500 || codigoHttp == 503 || codigoHttp == 504;
    }

    private void chamarGemini(String chaveApi, JSONArray parts, GeminiCallback callback, int tentativa) {
        final String modelo = MODELOS[Math.min(tentativa, MODELOS.length - 1)];
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelo + ":generateContent?key=" + chaveApi;

        try {
            JSONObject jsonBody = new JSONObject();
            JSONArray contentsArray = new JSONArray();
            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", parts);
            contentsArray.put(contentObj);
            jsonBody.put("contents", contentsArray);

            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            geminiClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    if (tentativa + 1 < MODELOS.length) {
                        Log.w(TAG, "Gemini falhou (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                                + ") no modelo " + modelo + "; tentando de novo (" + (tentativa + 2) + "/" + MODELOS.length + ")");
                        agendador.schedule(
                                () -> chamarGemini(chaveApi, parts, callback, tentativa + 1),
                                ESPERA_ENTRE_TENTATIVAS_MS * (tentativa + 1), TimeUnit.MILLISECONDS);
                        return;
                    }
                    callback.onFailure(e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful() || response.body() == null) {
                        String corpo = response.body() != null ? response.body().string() : "";
                        int codigo = response.code();
                        if (erroTemporario(codigo) && tentativa + 1 < MODELOS.length) {
                            Log.w(TAG, "Gemini HTTP " + codigo + " no modelo " + modelo
                                    + "; tentando de novo (" + (tentativa + 2) + "/" + MODELOS.length + ")"
                                    + " | corpo: " + corpo.substring(0, Math.min(300, corpo.length())).replace('\n', ' '));
                            agendador.schedule(
                                    () -> chamarGemini(chaveApi, parts, callback, tentativa + 1),
                                    ESPERA_ENTRE_TENTATIVAS_MS * (tentativa + 1), TimeUnit.MILLISECONDS);
                            return;
                        }
                        callback.onFailure(new IOException("Erro HTTP: " + codigo + " - " + response.message() + " | " + corpo));
                        return;
                    }

                    try {
                        String responseData = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseData);
                        String textoResposta = jsonResponse
                                .getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text");

                        callback.onSuccess(textoResposta);
                    } catch (JSONException e) {
                        callback.onFailure(e);
                    }
                }
            });

        } catch (JSONException e) {
            callback.onFailure(e);
        }
    }

    private void salvarRespostaNoFirebase(String uid, String texto) {
        DatabaseReference conversaRef = databaseRef.child("conversas").child(uid).push();
        Mensagem mensagem = new Mensagem(texto, "ia", System.currentTimeMillis());
        conversaRef.setValue(mensagem);
    }

    private interface ChamadaLiberada {
        void executar(String chaveApi, String sessao, boolean pedidoPendente) throws JSONException;
    }

    private interface AudioDownloadCallback {
        void onSuccess(String base64Audio, String mimeType);
        void onFailure(Exception e);
    }

    public interface GeminiCallback {
        void onSuccess(String resposta);
        void onFailure(Exception e);
    }
}