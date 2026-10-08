package com.florian.suporte60;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.TextUtils;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.ImageButton;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AdminChatActivity extends AppCompatActivity {

    public static final String EXTRA_USUARIO_ID = "usuarioId";
    public static final String EXTRA_NOME_USUARIO = "nomeUsuario";
    public static final String EXTRA_TELEFONE = "telefone";

    private ImageButton btnLigarChat;
    private String telefoneCliente;
    private static final int REQ_CALL_PHONE = 101;
    private static final String TAG_LIGACAO = "Suporte60Call";
    private boolean jaPediuEstadoTelefone = false;

    private RecyclerView recyclerViewMensagens;
    private MensagensAdapter mensagensAdapter;
    private List<Mensagem> listaMensagens = new ArrayList<>();
    private final List<Mensagem> audiosEmEnvio = new ArrayList<>();
    private DataSnapshot ultimoSnapshotConversa;
    private EditText etMensagem;
    private ImageButton btnEnviarTexto;
    private String usuarioId;
    private TextView tvStatusConexao;
    private MaterialButton btnAdminJaAtendido;

    private boolean serviceClosed = false;

    private boolean conectado = false;

    private LinearLayout layoutEscrita, layoutGravando;
    private ImageButton btnMicrofone, btnLixeiraAudio;
    private ImageView btnEnviarAudio;
    private TextView tvTempoGravacao;

    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private long startTime;
    private final android.os.Handler timerHandler = new android.os.Handler();
    private Runnable timerRunnable;

    private static final int REQ_RECORD_AUDIO = 100;
    private boolean permissaoJaFoiSolicitada = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_chat);

        usuarioId = getIntent().getStringExtra(EXTRA_USUARIO_ID);

        recyclerViewMensagens = findViewById(R.id.recyclerViewMensagens);
        etMensagem = findViewById(R.id.etMensagem);
        btnEnviarTexto = findViewById(R.id.btnEnviarTexto);
        tvStatusConexao = findViewById(R.id.tvStatusConexao);
        btnAdminJaAtendido = findViewById(R.id.btnAdminJaAtendido);
        btnLigarChat = findViewById(R.id.btnLigarChat);
        telefoneCliente = getIntent().getStringExtra(EXTRA_TELEFONE);

        layoutEscrita = findViewById(R.id.layoutEscrita);
        layoutGravando = findViewById(R.id.layoutGravando);
        btnMicrofone = findViewById(R.id.btnMicrofone);
        btnLixeiraAudio = findViewById(R.id.btnLixeiraAudio);
        btnEnviarAudio = findViewById(R.id.btnEnviarAudio);
        tvTempoGravacao = findViewById(R.id.tvTempoGravacao);

        aplicarInsetSuperior(findViewById(R.id.topBarAdminChat));
        aplicarInsetInferior(findViewById(R.id.barraInferiorAdminChat));

        monitorarConexao();

        TextView tvNomeUsuarioChat = findViewById(R.id.tvNomeUsuarioChat); tvNomeUsuarioChat.setText(getIntent().getStringExtra(EXTRA_NOME_USUARIO));

        mensagensAdapter = new MensagensAdapter(listaMensagens, true);
        recyclerViewMensagens.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewMensagens.setAdapter(mensagensAdapter);

        btnEnviarTexto.setOnClickListener(v -> enviarTexto());

        btnMicrofone.setOnClickListener(v -> pedirPermissaoEGravar());
        btnLixeiraAudio.setOnClickListener(v -> cancelarGravacao());
        btnEnviarAudio.setOnClickListener(v -> enviarAudio());

        btnAdminJaAtendido.setOnClickListener(v -> confirmarJaAtendido());

        btnLigarChat.setVisibility(View.VISIBLE);
        btnLigarChat.setOnClickListener(v -> abrirOpcoesTelefone());

        marcarConversaComoLida();

        escutarConversa();
    }

    private void ligarParaCliente() {
        String numero = normalizarTelefone(telefoneCliente);
        if (numero == null) {
            Toast.makeText(this, "Este cliente não tem um telefone válido cadastrado.", Toast.LENGTH_LONG).show();
            return;
        }

        boolean temCall = temPermissao(Manifest.permission.CALL_PHONE);
        boolean temEstado = temPermissao(Manifest.permission.READ_PHONE_STATE);
        if (!temCall || (!temEstado && !jaPediuEstadoTelefone)) {
            jaPediuEstadoTelefone = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE},
                    REQ_CALL_PHONE);
            return;
        }
        if (!temCall) return;

        PhoneAccountHandle conta = escolherContaComSinal();
        String problema = diagnosticarRedeCelular(conta);
        if (problema != null) {
            Toast.makeText(this, problema, Toast.LENGTH_LONG).show();
            return;
        }

        discar(numero, conta);
    }

    private void discar(String numero, PhoneAccountHandle conta) {
        String fim = numero.length() > 4 ? numero.substring(numero.length() - 4) : numero;
        Log.d(TAG_LIGACAO, "Discando: formato=" + numero.replaceAll("[0-9]", "9")
                + " (final " + fim + "), conta=" + (conta != null ? conta.getId() : "padrao do sistema"));

        Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", numero, null));
        if (conta != null) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, conta);
        }

        try {
            startActivity(intent);
        } catch (SecurityException e) {
            Toast.makeText(this, "Permissão de chamada negada. Acesse as configurações.", Toast.LENGTH_LONG).show();
            return;
        }
    }

    private boolean temPermissao(String permissao) {
        return ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    private String normalizarTelefone(String bruto) {
        if (bruto == null) return null;
        String t = bruto.trim();
        if (t.isEmpty() || t.equals("Não informado")) return null;
        boolean internacional = t.startsWith("+");
        String digitos = t.replaceAll("[^0-9]", "");
        if (digitos.length() < 8) return null;

        if (internacional) return "+" + digitos;
        int n = digitos.length();
        if ((n == 12 || n == 13) && digitos.startsWith("55")) return "+" + digitos;
        if (n == 10 || n == 11) return "+55" + digitos;
        return digitos;
    }

    private PhoneAccountHandle escolherContaComSinal() {
        try {
            TelecomManager telecom = getSystemService(TelecomManager.class);
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            if (telecom == null || tm == null) return null;
            List<PhoneAccountHandle> contas = telecom.getCallCapablePhoneAccounts();
            if (contas == null || contas.isEmpty()) return null;

            PhoneAccountHandle padrao = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
            PhoneAccountHandle primeiraComSinal = null;
            for (PhoneAccountHandle h : contas) {
                if (!contaEmServico(tm, h)) continue;
                if (h.equals(padrao)) return h;
                if (primeiraComSinal == null) primeiraComSinal = h;
            }
            return primeiraComSinal;
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG_LIGACAO, "Não foi possível listar as contas de chamada", e);
            return null;
        }
    }

    private boolean contaEmServico(TelephonyManager tm, PhoneAccountHandle h) {
        int subId = tm.getSubscriptionId(h);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return false;
        ServiceState ss = tm.createForSubscriptionId(subId).getServiceState();
        return ss != null && ss.getState() == ServiceState.STATE_IN_SERVICE;
    }

    private String diagnosticarRedeCelular(PhoneAccountHandle conta) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "Este aparelho não tem telefonia celular.";
        }
        if (Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            return "O modo avião está ligado. Desligue-o para fazer a ligação.";
        }

        TelephonyManager tm = getSystemService(TelephonyManager.class);
        if (tm == null) return null;

        boolean chipPronto = false;
        int slots = Math.max(1, tm.getActiveModemCount());
        for (int i = 0; i < slots; i++) {
            if (tm.getSimState(i) == TelephonyManager.SIM_STATE_READY) chipPronto = true;
        }
        if (!chipPronto) {
            return "Nenhum chip (SIM) pronto para uso. Verifique se o chip está inserido e desbloqueado.";
        }

        if (temPermissao(Manifest.permission.READ_PHONE_STATE)) {
            try {
                TelephonyManager alvo = tm;
                if (conta != null) {
                    int subId = tm.getSubscriptionId(conta);
                    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        alvo = tm.createForSubscriptionId(subId);
                    }
                }
                ServiceState ss = alvo.getServiceState();
                if (ss != null) {
                    Log.d(TAG_LIGACAO, "ServiceState=" + ss.getState() + " (0=em serviço, 1=sem serviço, 2=só emergência, 3=rádio desligado)");
                    switch (ss.getState()) {
                        case ServiceState.STATE_OUT_OF_SERVICE:
                            return "Sem sinal da operadora neste chip. Vá para um local com cobertura e tente de novo.";
                        case ServiceState.STATE_EMERGENCY_ONLY:
                            return "Rede disponível só para emergências. Verifique o plano/linha do chip.";
                        case ServiceState.STATE_POWER_OFF:
                            return "O rádio celular está desligado. Verifique o modo avião.";
                        default:
                            break;
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG_LIGACAO, "Sem permissão para ler o estado da rede", e);
            }
        }
        return null;
    }

    private void abrirOpcoesTelefone() {
        String[] opcoes = {"Ligar para o cliente", "Editar número do cliente"};
        new AlertDialog.Builder(this)
                .setTitle("Telefone do cliente")
                .setItems(opcoes, (dialog, which) -> {
                    if (which == 0) {
                        confirmarNumeroEFazerLigacao();
                    } else {
                        abrirEdicaoTelefoneDialog();
                    }
                })
                .show();
    }

    private void confirmarNumeroEFazerLigacao() {
        if (usuarioId == null || usuarioId.isEmpty()) {
            Toast.makeText(this, "Contato inválido.", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseDatabase.getInstance().getReference("usuarios_dados")
                .child(usuarioId).child("telefone")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String telefone = snapshot.exists() ? snapshot.getValue(String.class) : null;
                        telefoneCliente = telefone;

                        boolean temNumero = telefone != null && !telefone.trim().isEmpty()
                                && !telefone.equals("Não informado");
                        if (!temNumero) {
                            Toast.makeText(AdminChatActivity.this,
                                    "Este cliente não tem telefone salvo. Toque em \"Editar número do cliente\" para cadastrar um.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        ligarParaCliente();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(AdminChatActivity.this,
                                "Não foi possível verificar o telefone agora. Verifique a internet e tente de novo.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void abrirEdicaoTelefoneDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.layout_editar_telefone_admin);
        dialog.setCancelable(true);

        EditText etTelefoneAdmin = dialog.findViewById(R.id.etTelefoneAdminDialog);
        MaterialButton btnSalvar = dialog.findViewById(R.id.btnSalvarTelefoneAdmin);
        MaterialButton btnCancelar = dialog.findViewById(R.id.btnCancelarTelefoneAdmin);

        boolean temNumeroAtual = telefoneCliente != null && !telefoneCliente.trim().isEmpty()
                && !telefoneCliente.equals("Não informado");
        if (temNumeroAtual) {
            etTelefoneAdmin.setText(telefoneCliente);
            etTelefoneAdmin.setSelection(telefoneCliente.length());
        }

        btnCancelar.setOnClickListener(v -> dialog.dismiss());

        btnSalvar.setOnClickListener(v -> {
            String novoTelefone = etTelefoneAdmin.getText().toString().trim();
            if (TextUtils.isEmpty(novoTelefone)) {
                Toast.makeText(this, getString(R.string.erro_telefone_invalido), Toast.LENGTH_SHORT).show();
                return;
            }
            salvarTelefoneCliente(novoTelefone);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void salvarTelefoneCliente(String novoTelefone) {
        if (usuarioId == null || usuarioId.isEmpty()) return;

        FirebaseDatabase.getInstance().getReference("usuarios_dados")
                .child(usuarioId).child("telefone").setValue(novoTelefone)
                .addOnSuccessListener(unused -> {
                    telefoneCliente = novoTelefone;
                    atualizarTelefoneNoChamado(novoTelefone);
                    Toast.makeText(this, "Telefone atualizado.", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG_LIGACAO, "Falha ao salvar telefone do cliente", e);
                    String msg = (e instanceof DatabaseException
                            && String.valueOf(e.getMessage()).toLowerCase().contains("permission"))
                            ? "O Firebase recusou a gravação (sem permissão). Publique as regras atualizadas: firebase deploy --only database"
                            : "Não foi possível salvar o telefone. Verifique a internet e tente de novo.";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
    }

    private void atualizarTelefoneNoChamado(String novoTelefone) {
        DatabaseReference refChamado = FirebaseDatabase.getInstance()
                .getReference("chamados").child(usuarioId);
        refChamado.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists() || serviceClosed) return;
                refChamado.child("telefone").setValue(novoTelefone);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void aplicarInsetSuperior(View view) {
        if (view == null) return;
        final int paddingEsquerdoOriginal = view.getPaddingLeft();
        final int paddingTopoOriginal = view.getPaddingTop();
        final int paddingDireitoOriginal = view.getPaddingRight();
        final int paddingBaseOriginal = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(paddingEsquerdoOriginal, paddingTopoOriginal + statusBarTop,
                    paddingDireitoOriginal, paddingBaseOriginal);
            return insets;
        });
    }

    private void aplicarInsetInferior(View view) {
        if (view == null) return;
        final int paddingEsquerdoOriginal = view.getPaddingLeft();
        final int paddingTopoOriginal = view.getPaddingTop();
        final int paddingDireitoOriginal = view.getPaddingRight();
        final int paddingBaseOriginal = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(paddingEsquerdoOriginal, paddingTopoOriginal,
                    paddingDireitoOriginal, paddingBaseOriginal + navBarBottom);
            return insets;
        });
    }

    private void marcarConversaComoLida() {
        if (usuarioId == null || usuarioId.isEmpty()) return;
        DatabaseReference refChamado = FirebaseDatabase.getInstance()
                .getReference("chamados").child(usuarioId);

        refChamado.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists() || serviceClosed) return;
                java.util.Map<String, Object> leitura = new java.util.HashMap<>();
                leitura.put("lida", true);
                leitura.put("mensagensNaoLidas", 0);
                refChamado.updateChildren(leitura);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void confirmarJaAtendido() {
        new AlertDialog.Builder(this)
                .setTitle("Confirmar")
                .setMessage("Deseja realmente encerrar este atendimento e remover o contato da lista de espera?")
                .setPositiveButton("Sim", (dialog, which) -> excluirChamado())
                .setNegativeButton("Não", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void excluirChamado() {
        if (usuarioId == null || usuarioId.isEmpty()) {
            Toast.makeText(this, "Contato inválido: não foi possível encerrar.", Toast.LENGTH_LONG).show();
            return;
        }

        if (!conectado) {
            Toast.makeText(this,
                    "Sem conexão com o servidor. Reconecte para encerrar este atendimento.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this,
                    "Sessão do atendente não autenticada. Feche e abra o app novamente.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        serviceClosed = true;
        btnAdminJaAtendido.setEnabled(false);
        btnAdminJaAtendido.setText("Encerrando...");

        java.util.Map<String, Object> remocoes = new java.util.HashMap<>();
        remocoes.put("chamados/" + usuarioId, null);
        remocoes.put("controle_ia/" + usuarioId, null);
        String chaveAviso = FirebaseDatabase.getInstance().getReference("conversas")
                .child(usuarioId).push().getKey();
        java.util.Map<String, Object> aviso = new java.util.HashMap<>();
        aviso.put("texto", getString(R.string.aviso_atendimento_ia));
        aviso.put("enviadaPeloAtendente", true);
        aviso.put("remetente", "ia");
        aviso.put("timestamp", System.currentTimeMillis());
        remocoes.put("conversas/" + usuarioId + "/" + chaveAviso, aviso);

        FirebaseDatabase.getInstance().getReference("atendimentos_pendentes")
                .child(usuarioId).removeValue()
                .addOnFailureListener(e -> android.util.Log.w("AdminChatActivity",
                        "Não foi possível limpar atendimentos_pendentes/" + usuarioId
                                + " — publique as regras de database.rules.json.", e));

        final Runnable timeout = () -> {
            if (isFinishing() || isDestroyed()) return;
            reativarBotaoJaAtendido();
            Toast.makeText(this,
                    "O servidor não respondeu. Verifique a conexão e tente de novo.",
                    Toast.LENGTH_LONG).show();
        };
        timerHandler.postDelayed(timeout, 12000);

        FirebaseDatabase.getInstance().getReference()
                .updateChildren(remocoes)
                .addOnSuccessListener(ignorado -> {
                    timerHandler.removeCallbacks(timeout);
                    Toast.makeText(this, getString(R.string.contato_excluido), Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    timerHandler.removeCallbacks(timeout);
                    reativarBotaoJaAtendido();
                    android.util.Log.e("AdminChatActivity",
                            "Falha ao encerrar o atendimento de " + usuarioId, e);
                    Toast.makeText(this,
                            "Não foi possível encerrar o atendimento agora: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void reativarBotaoJaAtendido() {
        serviceClosed = false;
        btnAdminJaAtendido.setEnabled(true);
        btnAdminJaAtendido.setText("Já atendido");
    }

    private void monitorarConexao() {
        FirebaseDatabase.getInstance().getReference(".info/connected")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        conectado = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                        tvStatusConexao.setVisibility(conectado ? View.GONE : View.VISIBLE);
                        if (!conectado) tvStatusConexao.setText(R.string.erro_sem_internet);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private DatabaseReference refConversa;
    private ValueEventListener listenerConversa;

    private void escutarConversa() {
        if (usuarioId == null || usuarioId.isEmpty()) {
            Toast.makeText(this, "Contato inválido: conversa não pôde ser aberta.", Toast.LENGTH_LONG).show();
            return;
        }
        refConversa = FirebaseDatabase.getInstance()
                .getReference("conversas").child(usuarioId);

        listenerConversa = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                ultimoSnapshotConversa = snapshot;
                renderizarMensagens(snapshot);
            }
            @Override
            public void onCancelled(DatabaseError error) { }
        };
        refConversa.addValueEventListener(listenerConversa);
    }

    private void renderizarMensagens(DataSnapshot snapshot) {
        if (snapshot == null) return;
        listaMensagens.clear();
        for (DataSnapshot msgSnap : snapshot.getChildren()) {
            Mensagem m = msgSnap.getValue(Mensagem.class);
            if (m == null) continue;
            m.setId(msgSnap.getKey());
            listaMensagens.add(m);
        }
        listaMensagens.addAll(audiosEmEnvio);
        mensagensAdapter.notifyDataSetChanged();
        if (!listaMensagens.isEmpty()) {
            recyclerViewMensagens.scrollToPosition(listaMensagens.size() - 1);
        }
    }

    private void atualizarUltimaMensagemDoChamado(String resumo) {
        if (serviceClosed || usuarioId == null) return;
        FirebaseDatabase.getInstance().getReference("chamados")
                .child(usuarioId).child("ultimaMensagem").setValue(resumo);
    }

    private void enviarTexto() {
        String texto = etMensagem.getText().toString().trim();
        if (texto.isEmpty()) return;

        Mensagem msg = new Mensagem(texto, true);

        DatabaseReference refMsg = FirebaseDatabase.getInstance().getReference("conversas")
                .child(usuarioId).push();
        final String idMsg = refMsg.getKey();
        mensagensAdapter.marcarPendente(idMsg);
        refMsg.setValue(msg)
                .addOnSuccessListener(ignorado -> mensagensAdapter.marcarEnviada(idMsg))
                .addOnFailureListener(e -> { tvStatusConexao.setText(R.string.erro_envio_mensagem); tvStatusConexao.setVisibility(View.VISIBLE); });

        atualizarUltimaMensagemDoChamado("“Você: ”" + texto);

        etMensagem.setText("");
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

        final Mensagem provisoria = new Mensagem("", true, audioFile.getAbsolutePath(), duracao);
        provisoria.setId("local-" + System.nanoTime());
        mensagensAdapter.marcarPendente(provisoria.getId());
        audiosEmEnvio.add(provisoria);
        renderizarMensagens(ultimoSnapshotConversa);

        CloudinaryUploader.enviarAudio(audioFile, new CloudinaryUploader.UploadCallback() {
            @Override
            public void onSuccess(String urlSegura) {
                Mensagem mensagem = new Mensagem("", true, urlSegura, duracao);
                audiosEmEnvio.remove(provisoria);
                mensagensAdapter.esquecerPendente(provisoria.getId());
                DatabaseReference refMsg = FirebaseDatabase.getInstance().getReference("conversas")
                        .child(usuarioId).push();
                final String idMsg = refMsg.getKey();
                mensagensAdapter.marcarPendente(idMsg);
                refMsg.setValue(mensagem)
                        .addOnSuccessListener(ignorado -> {
                            mensagensAdapter.marcarEnviada(idMsg);
                            atualizarUltimaMensagemDoChamado("“Você: ”🎤 Áudio (" + duracao + ")");
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
                Toast.makeText(AdminChatActivity.this,
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
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
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
        if (requestCode == REQ_CALL_PHONE && temPermissao(Manifest.permission.CALL_PHONE)) {
            ligarParaCliente();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording) {
            pararGravacao();
            File f = new File(audioFilePath != null ? audioFilePath : "");
            if (f.exists()) f.delete();
        }
        if (mensagensAdapter != null) mensagensAdapter.liberarRecursos();
        if (refConversa != null && listenerConversa != null) {
            refConversa.removeEventListener(listenerConversa);
        }
        timerHandler.removeCallbacksAndMessages(null);
    }

}