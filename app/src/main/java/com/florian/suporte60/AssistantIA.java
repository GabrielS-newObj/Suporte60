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
    // Aviso enviado quando a 1ª rodada de tentativas falha: o app segue
    // tentando sozinho, o cliente não precisa reenviar nada.
    private static final String MENSAGEM_TENTANDO_NOVAMENTE =
            "Estou com instabilidade agora, mas vou tentar de novo automaticamente. " +
                    "Aguarde um instante, por favor.";
    // Só aparece se TODAS as rodadas falharem.
    private static final String MENSAGEM_FALHA_TEMPORARIA =
            "Não consegui responder mesmo tentando de novo. " +
                    "Pode enviar sua mensagem outra vez daqui a alguns minutos?";
    private static final String RESPOSTA_ENCAMINHANDO_ATENDENTE =
            "Entendi. Vou chamar um atendente para continuar com você. Aguarde um instante.";

    // CORREÇÃO: horário de atendimento (horário de Brasília, oficial). Fora
    // desse intervalo, a IA não deve nem tentar atender — só avisa o cliente
    // para voltar dentro do horário. "das 7h da manhã até às 20h da noite":
    // HORA_FIM é exclusivo (>= 20h já é fora do expediente).
    private static final int HORA_INICIO_ATENDIMENTO = 7;
    private static final int HORA_FIM_ATENDIMENTO = 20;
    // Brasil não tem mais horário de verão desde 2019, então este fuso é
    // sempre UTC-3 — não precisa de nenhum ajuste sazonal.
    private static final TimeZone FUSO_BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final String MENSAGEM_FORA_HORARIO =
            "No momento estamos fora do nosso horário de atendimento, que é das 7h às 20h. " +
                    "Por favor, volte dentro desse período que teremos prazer em te ajudar!";

    /**
     * Instruções enviadas junto com CADA mensagem (a chamada à Gemini não tem
     * histórico, então o contexto do "pedido anterior" vem de controle_ia).
     *
     * @param audio           true = a mensagem do cliente é o áudio anexado
     * @param pedidoPendente  true = na mensagem anterior o cliente já pediu um
     *                        atendente e a IA ofereceu continuar ajudando
     */
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

    // Cloudinary/OkHttp aceitam qualquer tamanho, mas evitamos mandar áudios
    // enormes em base64 pra Gemini (o limite recomendado pra inline_data é
    // baixo; acima disso o ideal seria a Files API, que este fluxo simples
    // não usa).
    private static final long TAMANHO_MAXIMO_AUDIO_BYTES = 15L * 1024 * 1024; // 15 MB

    private final DatabaseReference databaseRef;
    // CORREÇÃO: sem timeouts explícitos, uma chamada travada deixava o cliente
    // sem resposta por muito tempo e a falha só chegava depois — quando o
    // atendente já podia ter clicado em "Já atendido".
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    // A Gemini pode demorar mais que o resto (sobretudo com áudio inline).
    // Cliente separado para não afetar o download do áudio no Cloudinary.
    // O timeout é de 20 s de leitura POR TENTATIVA: com até 5 tentativas
    // trocando de modelo, esperar 45 s em cada uma deixaria o cliente minutos
    // sem resposta quando a Gemini está instável.
    private final OkHttpClient geminiClient = httpClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    public AssistantIA() {
        this.databaseRef = FirebaseDatabase.getInstance().getReference();
    }

    private static final int LIMITE_RESPOSTAS_IA = 20; // quantas respostas a IA dá antes de chamar o atendente

    /** Quanto tempo a IA espera uma resposta do cliente antes de repetir o aviso. */
    public static final long LIMITE_ESPERA_CLIENTE_MS = 30 * 60 * 1000L;

    /** Quanto tempo a mensagem "sessão encerrada" fica na tela antes de o histórico ser limpo. */
    public static final long ATRASO_LIMPEZA_HISTORICO_MS = 10 * 1000L;

    /**
     * TEMPO LIMITE DE ESPERA: se a IA respondeu e o cliente ficou 30 min ou
     * mais sem escrever, a SESSÃO É ENCERRADA:
     *
     *   1. controle_ia/{uid} é apagado — o mesmo reset do "Já atendido":
     *      countIA volta a zero (20 tentativas novas da IA), a "sessao"
     *      morre e a próxima mensagem do cliente abre uma conversa nova;
     *   2. chamados/{uid} e atendimentos_pendentes/{uid} são apagados — o
     *      contato sai da lista do atendente (app server);
     *   3. uma mensagem da IA avisando do encerramento é gravada em
     *      conversas/{uid}. Depois de ATRASO_LIMPEZA_HISTORICO_MS a
     *      MainActivity apaga o histórico até essa mensagem, e o chat volta
     *      a mostrar só a mensagem de boas-vindas do assistente virtual.
     *
     * Chamado pela MainActivity quando o prazo vence (timer com o app aberto)
     * ou quando o app é aberto depois do prazo. Como os dois caminhos podem
     * disparar juntos, o "reset" é a própria transação em controle_ia/{uid}:
     * só quem de fato apagou o nó (publicar = true) grava a mensagem e limpa
     * o chamado — garante UM encerramento por período de espera.
     *
     * Não faz nada se não há sessão (atendimento já encerrado / conversa ainda
     * não começou) ou se o atendente humano já assumiu (escalado).
     */
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

                // Devolve os dados SEM alterar (em vez de abort()) para o SDK
                // confirmar o estado no servidor, como nas outras transações.
                if (escalado || semSessao || aindaNoPrazo) {
                    return Transaction.success(dados);
                }
                dados.setValue(null); // reset total da IA (countIA, sessao, ...)
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

    /** Tira o contato da lista do atendente e grava o aviso de sessão encerrada. */
    private void concluirEncerramentoPorInatividade(String uid, String textoEncerramento) {
        Map<String, Object> atualizacoes = new HashMap<>();
        // Some da lista do atendente (mesmo efeito do "Já atendido").
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

        // Fora do bloco atômico, de propósito (mesmo motivo do "Já atendido"):
        // se as regras publicadas ainda forem as antigas, uma negação aqui
        // não derruba as outras escritas.
        databaseRef.child("atendimentos_pendentes").child(uid).removeValue()
                .addOnFailureListener(e -> Log.w(TAG,
                        "Não foi possível limpar atendimentos_pendentes/" + uid, e));
    }

    /** Processa uma mensagem de TEXTO enviada pelo cliente. */
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

    /**
     * Processa uma mensagem de ÁUDIO enviada pelo cliente: baixa o arquivo
     * já hospedado no Cloudinary (ver CloudinaryUploader/MainActivity.enviarAudio),
     * converte para base64 e manda pra Gemini como "inline_data" de áudio,
     * pra IA ouvir e interpretar o conteúdo diretamente — sem depender de
     * nenhum passo manual de transcrição. O download é feito UMA vez; as
     * rodadas de nova tentativa reaproveitam o base64 já em memória.
     */
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

    /**
     * Uma rodada inteira (todos os modelos) falhou. Se ainda restam rodadas,
     * avisa o cliente (só na 1ª falha) e agenda nova rodada; se não restam,
     * ou se a sessão já acabou / o atendente já assumiu, encerra.
     * Nunca escala para o atendente por falha técnica.
     */
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

    /** Decisão tomada, de forma atômica, sobre uma mensagem do cliente. */
    private enum Decision { REPLY, ESCALATE, SILENCE, OUT_OF_HOURS }

    /** O que fazer quando a chamada à IA termina (com sucesso ou falha). */
    private enum Destino { PUBLICAR, DESCARTAR, TENTAR_DE_NOVO, ESCALATE }

    private DatabaseReference controleRef(String uid) {
        return databaseRef.child("controle_ia").child(uid);
    }

    private interface HoraCallback {
        void onResultado(boolean dentroDoHorarioComercial);
    }

    /**
     * CORREÇÃO: checagem do horário de atendimento (Brasília, oficial) antes
     * de abrir uma nova conversa com a IA. Usa o relógio dos SERVIDORES do
     * Firebase (".info/serverTimeOffset"), não o relógio do aparelho do
     * cliente — que pode estar com data/hora errada ou alterada manualmente.
     * offset = diferença (servidor - aparelho) em ms; somando ao
     * currentTimeMillis() local chegamos na hora real do servidor.
     */
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
                // Sem conexão com o Firebase para consultar o horário oficial:
                // não bloqueia o cliente por um problema técnico nosso, deixa
                // passar como se estivesse dentro do horário.
                Log.w(TAG, "Não foi possível obter o horário oficial (Firebase); seguindo sem checar horário.");
                callback.onResultado(true);
            }
        });
    }

    /**
     * Passo comum a processar()/processarAudio(): decide se a IA responde,
     * fica em silêncio (atendente já assumiu) ou escala (limite atingido).
     *
     * Estado em controle_ia/{uid}:
     *   pedidoAtendente true = o cliente pediu um atendente humano e a IA
     *                   ofereceu continuar ajudando; se ele pedir de novo na
     *                   mensagem seguinte, a IA encaminha (ver finalizarComSucesso)
     *   countIA      quantas respostas a IA já reservou nesta "sessão"
     *   escalado        true = atendente humano assumiu, a IA fica calada
     *   sessao          identificador desta sessão (UUID). Nasce na 1ª mensagem
     *                   e morre junto com o nó quando o atendente clica em
     *                   "Já atendido" (que apaga controle_ia/{uid}).
     *   falhasSeguidas  (obsoleto) falhas não escalam mais; o campo é apenas limpo
     *
     * HORÁRIO DE ATENDIMENTO: se a mensagem que chegaria a criar uma "sessao"
     * NOVA cair fora do horário de atendimento (hora oficial de Brasília,
     * ver obterHoraOficialBrasilia), a IA não cria a sessão nem incrementa
     * "countIA" — só responde com o aviso de horário. Cada nova mensagem
     * do cliente enquanto isso persistir repete a mesma checagem.
     *
     * POR QUE EXISTE A "sessao": toda chamada à Gemini é assíncrona e pode
     * terminar segundos depois — inclusive DEPOIS de o atendente clicar em
     * "Já atendido". Antes, o callback dessa chamada gravava "escalado = true"
     * (e uma mensagem "vou chamar um atendente") sem olhar o estado atual,
     * ressuscitando o nó que o atendente tinha acabado de apagar. A próxima
     * mensagem do cliente caía direto no fluxo do atendente. Agora cada
     * chamada carrega a sessão em que nasceu e só consegue escrever se essa
     * MESMA sessão ainda existir no servidor (ver finalizarComSucesso/Falha).
     */
    private void prepararChamada(String uid, ChamadaLiberada acao) {
        final String chaveApi = BuildConfig.GEMINI_API_KEY;

        // CORREÇÃO: o horário oficial (Brasília) é consultado ANTES da
        // transação — doTransaction() precisa ser síncrono/puro, então não
        // pode fazer essa leitura de rede sozinho. O resultado (dentroDoHorario)
        // só é usado lá dentro para decidir se uma CONVERSA NOVA pode começar;
        // uma conversa já em andamento nunca é interrompida por causa da hora.
        obterHoraOficialBrasilia(dentroDoHorario ->
                prepararChamadaTransacao(uid, acao, chaveApi, dentroDoHorario));
    }

    private void prepararChamadaTransacao(String uid, ChamadaLiberada acao, String chaveApi,
                                          boolean dentroDoHorario) {
        // Guarda a decisão da ÚLTIMA execução de doTransaction() (o SDK pode
        // reexecutá-lo se o valor do servidor for diferente do cache local).
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

                // Atendente humano cuidando: a IA fica em silêncio. Devolve os
                // dados SEM alterar (em vez de abort()) para o SDK confirmar
                // esse estado no servidor, e não só no cache local.
                if (escalado) {
                    decisao.set(Decision.SILENCE);
                    return Transaction.success(dados);
                }

                // Sessão nova (nó não existe: 1ª mensagem ou logo após o
                // "Já atendido") ou sessão em andamento (reaproveita).
                String sessao = dados.child("sessao").getValue(String.class);
                boolean conversaNova = (sessao == null || sessao.isEmpty());

                // CORREÇÃO: fora do horário de atendimento, uma conversa NOVA
                // nem chega a começar — não cria "sessao" e não mexe em
                // "countIA" (não conta como tentativa/atendimento, é só um
                // aviso automático de horário). Uma conversa já em andamento
                // (sessao != null) segue normalmente, mesmo que o horário
                // tenha virado no meio dela.
                if (conversaNova && !dentroDoHorario) {
                    decisao.set(Decision.OUT_OF_HOURS);
                    return Transaction.success(dados); // não altera nada
                }

                if (conversaNova) {
                    sessao = UUID.randomUUID().toString();
                    dados.child("sessao").setValue(sessao);
                }
                sessaoRef.set(sessao);
                // O cliente já pediu um atendente (e a IA ofereceu continuar
                // ajudando)? Se pedir de novo, é insistência -> escala.
                pedidoPendenteRef.set(Boolean.TRUE.equals(
                        dados.child("pedidoAtendente").getValue(Boolean.class)));

                // Limite atingido: encaminha para o atendente
                if (contagem >= LIMITE_RESPOSTAS_IA) {
                    decisao.set(Decision.ESCALATE);
                    dados.child("escalado").setValue(true);
                    return Transaction.success(dados);
                }

                // Reserva a vaga desta resposta ANTES de chamar a IA
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
                        // Resposta única, automática — não passa pela Gemini
                        // e não fica registrada como tentativa de atendimento.
                        salvarRespostaNoFirebase(uid, MENSAGEM_FORA_HORARIO);
                        return;

                    case ESCALATE:
                        // "escalado" já foi gravado dentro da transação
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

    /**
     * A vaga já foi reservada em prepararChamada(). Aqui só se publica a
     * resposta — e SÓ se a sessão em que a pergunta nasceu ainda for a atual.
     * Se o atendente encerrou o atendimento enquanto a IA pensava, a resposta
     * é descartada (nada é escrito no banco).
     */
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
                    return Transaction.success(dados); // não altera nada
                }

                dados.child("falhasSeguidas").setValue(null); // IA voltou a funcionar

                if (!pediuAtendente) {
                    // Falou de outra coisa: o pedido anterior deixa de valer
                    // (só conta como insistência se for em mensagens seguidas).
                    dados.child("pedidoAtendente").setValue(null);
                    destino.set(Destino.PUBLICAR);
                    return Transaction.success(dados);
                }

                boolean jaTinhaPedido = Boolean.TRUE.equals(
                        dados.child("pedidoAtendente").getValue(Boolean.class));
                if (jaTinhaPedido) {
                    // Insistiu: encaminha para o atendente humano
                    dados.child("pedidoAtendente").setValue(null);
                    dados.child("escalado").setValue(true);
                    destino.set(Destino.ESCALATE);
                } else {
                    // 1º pedido: a IA tenta ajudar e avisa que, se ele
                    // pedir de novo, será encaminhado
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
                        // "escalado" já foi gravado dentro da transação
                        salvarRespostaNoFirebase(uid, RESPOSTA_ENCAMINHANDO_ATENDENTE);
                        databaseRef.child("atendimentos_pendentes").child(uid).setValue(true);
                        return;
                    default:
                        Log.i(TAG, "Resposta da IA descartada: o atendimento foi encerrado enquanto ela era gerada.");
                }
            }
        });
    }

    /**
     * Falha da Gemini (rede, HTTP, timeout, JSON...). Regras:
     *  - sessão já encerrada pelo atendente -> descarta, NÃO escreve nada
     *    (é exatamente a escrita atrasada que quebrava o reset);
     *  - devolve a vaga reservada, para a falha não consumir o limite de respostas da IA;
     *  - NUNCA escala para o atendente: falha técnica não é pedido do cliente.
     *    O atendente só é chamado quando o cliente pede (e insiste) ou quando o
     *    limite de respostas da IA é atingido (ver prepararChamada/finalizarComSucesso).
     *    Nesse caso o cliente só recebe um aviso para tentar de novo.
     */
    private void finalizarComFalha(String uid, String sessao) {
        final AtomicReference<Destino> destino = new AtomicReference<>(Destino.DESCARTAR);

        controleRef(uid).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData dados) {
                String atual = dados.child("sessao").getValue(String.class);
                if (sessao == null || !sessao.equals(atual)) {
                    destino.set(Destino.DESCARTAR);
                    return Transaction.success(dados); // não altera nada
                }

                Long c = dados.child("countIA").getValue(Long.class);
                long contagem = (c == null) ? 0L : c;
                dados.child("countIA").setValue(Math.max(0L, contagem - 1)); // devolve a vaga

                dados.child("falhasSeguidas").setValue(null); // campo antigo, não é mais usado
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

    /**
     * Baixa o áudio do Cloudinary (a mesma URL "secure_url" salva na
     * mensagem) e converte para base64, no formato que a Gemini API espera
     * em "inline_data". O áudio foi gravado no app como AAC dentro de um
     * container .mp4 (ver MainActivity.iniciarGravacao), então o mime type
     * enviado pra Gemini é "audio/aac", que está na lista oficial de
     * formatos de áudio suportados pela API.
     */
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

    /**
     * Chamada genérica à Gemini generateContent — aceita tanto "parts" só de
     * texto quanto "parts" com texto + inline_data (áudio), já que o formato
     * do request body é o mesmo pra ambos os casos.
     */
    private void chamarGemini(String chaveApi, JSONArray parts, GeminiCallback callback) {
        chamarGemini(chaveApi, parts, callback, 0);
    }

    /** Erros temporários do servidor: vale tentar de novo / trocar de modelo. */
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