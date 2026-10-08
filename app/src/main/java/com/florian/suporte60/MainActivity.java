package com.florian.suporte60;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private String AVISO_ATENDIMENTO_IA;

    private String AVISO_SESSAO_ENCERRADA;

    private static final long TRINTA_MINUTOS_MS = AssistantIA.LIMITE_ESPERA_CLIENTE_MS;

    private FirebaseAuth mAuth;
    private String uid;

    private RecyclerView recyclerViewMensagens;
    private MensagensAdapter mensagensAdapter;
    private List<Mensagem> listaMensagens = new ArrayList<>();
    private EditText etMensagem;
    private ImageButton btnEnviarTexto;

    private LinearLayout layoutEscrita, layoutGravando;
    private ImageButton btnMicrofone, btnLixeiraAudio, btnEnviarAudio;;
    private TextView tvTempoGravacao;
    private MaterialButton btnAcaoTopo;

    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private long startTime;
    private final android.os.Handler timerHandler = new android.os.Handler();
    private Runnable timerRunnable;

    private static final int REQ_RECORD_AUDIO = 100;
    private boolean permissaoJaFoiSolicitada = false;
    private TextView tvStatusConexao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AVISO_ATENDIMENTO_IA = getString(R.string. aviso_atendimento_ia);
        AVISO_SESSAO_ENCERRADA = getString(R.string.sessao_encerrada_ia);

        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() != null) {
            uid = mAuth.getCurrentUser().getUid();
            inicializarComponentes();
            verificarTelefoneUsuario();
            carregarMensagens();
        } else {
            mAuth.signInAnonymously().addOnCompleteListener(this, task -> {
                if (!task.isSuccessful() || mAuth.getCurrentUser() == null) {
                    Toast.makeText(MainActivity.this, getString(R.string. erro_conexao), Toast.LENGTH_LONG).show();
                    return;
                }
                uid = mAuth.getCurrentUser().getUid();
                inicializarComponentes();
                verificarTelefoneUsuario();
                carregarMensagens();
            });
        }
    }


    private void monitorarConexao() { FirebaseDatabase.getInstance().getReference(".info/connected") .addValueEventListener(new ValueEventListener() { @Override public void onDataChange(@NonNull DataSnapshot snapshot) { boolean conectado = Boolean.TRUE.equals(snapshot.getValue(Boolean.class)); tvStatusConexao.setVisibility(conectado ? View.GONE : View.VISIBLE); if (!conectado) tvStatusConexao.setText(R.string.erro_sem_internet); } @Override public void onCancelled(@NonNull DatabaseError error) {} }); }

    private void inicializarComponentes() {
        recyclerViewMensagens = findViewById(R.id.recyclerViewMensagens);
        etMensagem = findViewById(R.id.etMensagem);
        btnEnviarTexto = findViewById(R.id.btnEnviarTexto);
        layoutEscrita = findViewById(R.id.layoutEscrita);
        layoutGravando = findViewById(R.id.layoutGravando);
        btnMicrofone = findViewById(R.id.btnMicrofone);
        btnLixeiraAudio = findViewById(R.id.btnLixeiraAudio);
        btnEnviarAudio = findViewById(R.id.btnEnviarAudio);
        tvTempoGravacao = findViewById(R.id.tvTempoGravacao);
        btnAcaoTopo = findViewById(R.id.btnAcaoTopo);
        tvStatusConexao = findViewById(R.id.tvStatusConexao);

        aplicarInsetSuperior(findViewById(R.id.topBarCliente));
        aplicarInsetInferior(findViewById(R.id.barraInferiorCliente));

        monitorarConexao();

        mensagensAdapter = new MensagensAdapter(listaMensagens, false);
        recyclerViewMensagens.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewMensagens.setAdapter(mensagensAdapter);

        btnAcaoTopo.setOnClickListener(v -> abrirModalTutorial());

        btnEnviarTexto.setOnClickListener(v -> enviarTexto());

        btnMicrofone.setOnClickListener(v -> pedirPermissaoEGravar());
        btnLixeiraAudio.setOnClickListener(v -> cancelarGravacao());
        btnEnviarAudio.setOnClickListener(v -> enviarAudio());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }

    private final android.os.Handler handlerLimiteEspera = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean listenerConversaRegistrado = false;
    private DataSnapshot ultimoSnapshotConversa;
    private final List<Mensagem> audiosEmEnvio = new ArrayList<>();

    private void carregarMensagens() {
        DatabaseReference refControle = FirebaseDatabase.getInstance()
                .getReference("controle_ia").child(uid);
        refControle.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                agendarAvisoLimiteEspera(snapshot);
                if (!listenerConversaRegistrado) {
                    escutarConversa();
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!listenerConversaRegistrado) escutarConversa();
            }
        });
    }

    private void agendarAvisoLimiteEspera(DataSnapshot controle) {
        handlerLimiteEspera.removeCallbacksAndMessages(null);

        String sessao = controle.child("sessao").getValue(String.class);
        boolean escalado = Boolean.TRUE.equals(controle.child("escalado").getValue(Boolean.class));
        Long ultima = controle.child("ultimaInteracao").getValue(Long.class);

        if (sessao == null || sessao.isEmpty() || escalado || ultima == null || ultima <= 0L) return;

        long espera = Math.max(0L, ultima + TRINTA_MINUTOS_MS - System.currentTimeMillis());
        handlerLimiteEspera.postDelayed(
                () -> new AssistantIA().encerrarSessaoPorInatividade(uid, AVISO_SESSAO_ENCERRADA),
                espera);
    }

    private final android.os.Handler handlerLimpezaHistorico = new android.os.Handler(android.os.Looper.getMainLooper());
    private String limpezaAgendadaParaChave;

    private void agendarLimpezaHistorico(String chaveEncerramento, long timestampEncerramento) {
        if (chaveEncerramento.equals(limpezaAgendadaParaChave)) return;
        limpezaAgendadaParaChave = chaveEncerramento;

        long restante = timestampEncerramento + AssistantIA.ATRASO_LIMPEZA_HISTORICO_MS
                - System.currentTimeMillis();
        long espera = Math.min(AssistantIA.ATRASO_LIMPEZA_HISTORICO_MS, Math.max(0L, restante));
        handlerLimpezaHistorico.postDelayed(() -> limparHistoricoAte(chaveEncerramento), espera);
    }

    private void limparHistoricoAte(String chaveLimite) {
        if (uid == null) return;
        final DatabaseReference refConversa = FirebaseDatabase.getInstance()
                .getReference("conversas").child(uid);
        refConversa.orderByKey().endAt(chaveLimite)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Map<String, Object> remocoes = new HashMap<>();
                        for (DataSnapshot filho : snapshot.getChildren()) {
                            if (filho.getKey() != null) remocoes.put(filho.getKey(), null);
                        }
                        if (!remocoes.isEmpty()) refConversa.updateChildren(remocoes);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        limpezaAgendadaParaChave = null;
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handlerLimiteEspera.removeCallbacksAndMessages(null);
        handlerLimpezaHistorico.removeCallbacksAndMessages(null);
    }

    private void escutarConversa() {
        listenerConversaRegistrado = true;
        DatabaseReference refConversa = FirebaseDatabase.getInstance()
                .getReference("conversas").child(uid);
        refConversa.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                ultimoSnapshotConversa = snapshot;
                renderizarMensagens(snapshot);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.erro_carregar_mensagens), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderizarMensagens(DataSnapshot snapshot) {
        if (snapshot == null) return;
        listaMensagens.clear();
        boolean jaTemAviso = false;
        String chaveEncerramento = null;
        long timestampEncerramento = 0L;
        for (DataSnapshot msgSnap : snapshot.getChildren()) {
            Mensagem m = msgSnap.getValue(Mensagem.class);
            if (m == null) continue;
            m.setId(msgSnap.getKey());
            listaMensagens.add(m);
            if (AVISO_ATENDIMENTO_IA.equals(m.getTexto())) jaTemAviso = true;
            if ("ia".equals(m.getRemetente()) && AVISO_SESSAO_ENCERRADA.equals(m.getTexto())) {
                chaveEncerramento = msgSnap.getKey();
                timestampEncerramento = m.getTimestamp();
            }
        }
        listaMensagens.addAll(audiosEmEnvio);
        if (chaveEncerramento != null) {
            agendarLimpezaHistorico(chaveEncerramento, timestampEncerramento);
        } else {
            limpezaAgendadaParaChave = null;
        }
        if (!jaTemAviso) {
            listaMensagens.add(0, new Mensagem(AVISO_ATENDIMENTO_IA, true));
        }
        mensagensAdapter.notifyDataSetChanged();
        if (!listaMensagens.isEmpty()) {
            recyclerViewMensagens.scrollToPosition(listaMensagens.size() - 1);
        }
    }



    private void enviarTexto() {
        String texto = etMensagem.getText().toString().trim();
        if (texto.isEmpty()) return;

        Mensagem msg = new Mensagem(texto, false);

        DatabaseReference refMsg = FirebaseDatabase.getInstance().getReference("conversas")
                .child(uid).push();
        final String idMsg = refMsg.getKey();
        mensagensAdapter.marcarPendente(idMsg);
        refMsg.setValue(msg)
                .addOnSuccessListener(ignorado -> {
                    mensagensAdapter.marcarEnviada(idMsg);
                    new AssistantIA().processar(uid, texto);
                    atualizarChamado(texto);
                })
                .addOnFailureListener(e -> {
                    tvStatusConexao.setText(R.string.erro_envio_mensagem);
                    tvStatusConexao.setVisibility(View.VISIBLE);
                });

        etMensagem.setText("");
    }

    private void atualizarChamado(String ultimaMensagem) {
        if (uid == null) return;

        FirebaseDatabase.getInstance().getReference("usuarios_dados")
                .child(uid).child("telefone")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String telefone = snapshot.exists() ? snapshot.getValue(String.class) : null;
                        salvarChamado(ultimaMensagem, telefone);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        salvarChamado(ultimaMensagem, null);
                    }
                });
    }

    private void salvarChamado(String ultimaMensagem, String telefone) {
        DatabaseReference refChamado = FirebaseDatabase.getInstance().getReference("chamados").child(uid);

        Map<String, Object> dados = new HashMap<>();
        dados.put("id", uid);
        dados.put("usuarioId", uid);
        dados.put("nomeUsuario", "Cliente " + uid.substring(0, Math.min(6, uid.length())));
        dados.put("ultimaMensagem", ultimaMensagem);
        dados.put("status", "aguardando");
        dados.put("telefone", (telefone == null || telefone.trim().isEmpty()) ? "Não informado" : telefone);
        dados.put("lida", false);

        refChamado.updateChildren(dados);

        refChamado.child("mensagensNaoLidas").runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData mutableData) {
                Integer atual = mutableData.getValue(Integer.class);
                mutableData.setValue((atual == null ? 0 : atual) + 1);
                return Transaction.success(mutableData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {}
        });
    }

    private void pedirPermissaoEGravar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                    && permissaoJaFoiSolicitada) {
                Toast.makeText(this,
                        "Permita o uso do microfone nas Configurações do app para gravar áudios.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            permissaoJaFoiSolicitada = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        iniciarGravacao();
    }

    private void iniciarGravacao() {
        layoutEscrita.setVisibility(View.GONE);
        layoutGravando.setVisibility(View.VISIBLE);
        audioFilePath = getExternalCacheDir().getAbsolutePath() + "/audio_temp_" + System.currentTimeMillis() + ".mp4";

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioChannels(1);
        mediaRecorder.setAudioSamplingRate(16000);
        mediaRecorder.setAudioEncodingBitRate(64000);
        mediaRecorder.setOutputFile(audioFilePath);
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            iniciarCronometro();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            Toast.makeText(this, "Não foi possível iniciar a gravação. Verifique se outro app não está usando o microfone.", Toast.LENGTH_LONG).show();
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            cancelarGravacao();
        }
    }

    private void cancelarGravacao() {
        pararGravacao();
        File f = new File(audioFilePath != null ? audioFilePath : "");
        if (f.exists()) f.delete();
        layoutGravando.setVisibility(View.GONE);
        layoutEscrita.setVisibility(View.VISIBLE);
    }

    private void enviarAudio() {
        String duracao = tvTempoGravacao.getText().toString();
        pararGravacao();
        layoutGravando.setVisibility(View.GONE);
        layoutEscrita.setVisibility(View.VISIBLE);

        if (audioFilePath == null) return;
        File audioFile = new File(audioFilePath);
        if (!audioFile.exists() || audioFile.length() == 0) {
            Toast.makeText(this, "Áudio muito curto, tente novamente.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Enviando áudio...", Toast.LENGTH_SHORT).show();

        final Mensagem provisoria = new Mensagem("", false, audioFile.getAbsolutePath(), duracao);
        provisoria.setId("local-" + System.nanoTime());
        mensagensAdapter.marcarPendente(provisoria.getId());
        audiosEmEnvio.add(provisoria);
        renderizarMensagens(ultimoSnapshotConversa);

        CloudinaryUploader.enviarAudio(audioFile, new CloudinaryUploader.UploadCallback() {
            @Override
            public void onSuccess(String urlSegura) {
                Mensagem mensagem = new Mensagem("", false, urlSegura, duracao);
                audiosEmEnvio.remove(provisoria);
                mensagensAdapter.esquecerPendente(provisoria.getId());
                DatabaseReference refMsg = FirebaseDatabase.getInstance().getReference("conversas")
                        .child(uid).push();
                final String idMsg = refMsg.getKey();
                mensagensAdapter.marcarPendente(idMsg);
                refMsg.setValue(mensagem)
                        .addOnSuccessListener(ignorado -> {
                            mensagensAdapter.marcarEnviada(idMsg);
                            atualizarChamado("🎤 Áudio (" + duracao + ")");
                            new AssistantIA().processarAudio(uid, urlSegura);
                        })
                        .addOnFailureListener(e -> {
                            tvStatusConexao.setText(R.string.erro_envio_mensagem);
                            tvStatusConexao.setVisibility(View.VISIBLE);
                        });
            }

            @Override
            public void onFailure(String mensagemErro) {
                audiosEmEnvio.remove(provisoria);
                mensagensAdapter.esquecerPendente(provisoria.getId());
                renderizarMensagens(ultimoSnapshotConversa);
                Toast.makeText(MainActivity.this,
                        "Não foi possível enviar o áudio: " + mensagemErro,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void pararGravacao() {
        if (isRecording) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            pararCronometro();
        }
    }

    private void iniciarCronometro() {
        startTime = SystemClock.uptimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long milliseconds = SystemClock.uptimeMillis() - startTime;
                int seconds = (int) (milliseconds / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;
                tvTempoGravacao.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void pararCronometro() {
        timerHandler.removeCallbacks(timerRunnable);
        tvTempoGravacao.setText("00:00");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarGravacao();
        }
    }

    private void aplicarInsetSuperior(View view) {
        if (view == null) return;
        final int esquerdo = view.getPaddingLeft();
        final int topo = view.getPaddingTop();
        final int direito = view.getPaddingRight();
        final int base = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(esquerdo, topo + statusBarTop, direito, base);
            return insets;
        });
    }

    private void aplicarInsetInferior(View view) {
        if (view == null) return;
        final int esquerdo = view.getPaddingLeft();
        final int topo = view.getPaddingTop();
        final int direito = view.getPaddingRight();
        final int base = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(esquerdo, topo, direito, base + navBarBottom);
            return insets;
        });
    }

    private void abrirModalTutorial() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.layout_tutorial);
        dialog.setCancelable(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.80),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.80)
            );

            window.setBackgroundDrawableResource(android.R.color.transparent);

            window.getAttributes().windowAnimations = android.R.style.Animation_Dialog;

            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(params);
        }

        TextView btnFechar = dialog.findViewById(R.id.btnFecharTutorial);
        btnFechar.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void verificarTelefoneUsuario() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;
        String uidAtual = currentUser.getUid();
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("usuarios_dados")
                .child(uidAtual).child("telefone");
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    pedirTelefoneDialog(uidAtual);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void pedirTelefoneDialog(String uidAtual) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.layout_telefone_dialog);
        dialog.setCancelable(true);

        MaterialButton btnPular = dialog.findViewById(R.id.btnPularTelefone); btnPular.setOnClickListener(v -> dialog.dismiss());

        EditText etTelefone = dialog.findViewById(R.id.etTelefoneDialog);
        MaterialButton btnSalvar = dialog.findViewById(R.id.btnSalvarTelefone);

        btnSalvar.setOnClickListener(v -> {
            String telefone = etTelefone.getText().toString().trim();
            if (TextUtils.isEmpty(telefone)) {
                Toast.makeText(MainActivity.this, getString(R.string. erro_telefone_invalido), Toast.LENGTH_SHORT).show();
                return;
            }
            FirebaseDatabase.getInstance().getReference("usuarios_dados")
                    .child(uidAtual).child("telefone").setValue(telefone);
            dialog.dismiss();
        });

        dialog.show();
    }


}