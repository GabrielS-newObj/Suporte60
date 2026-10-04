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
    // CORREÇÃO: novo extra — o telefone já vem no objeto Chamado que a lista
    // (AdminChamadosActivity) tem em mãos, então é repassado aqui em vez de
    // fazer uma segunda leitura no Firebase só para isso.
    public static final String EXTRA_TELEFONE = "telefone";

    private ImageButton btnLigarChat;
    private String telefoneCliente;
    private static final int REQ_CALL_PHONE = 101;
    private static final String TAG_LIGACAO = "Suporte60Call";
    /** Evita pedir READ_PHONE_STATE em loop se o atendente negar. */
    private boolean jaPediuEstadoTelefone = false;

    private RecyclerView recyclerViewMensagens;
    private MensagensAdapter mensagensAdapter;
    private List<Mensagem> listaMensagens = new ArrayList<>();
    // áudios gravados que ainda estão subindo para o Cloudinary (balão provisório 🕓)
    private final List<Mensagem> audiosEmEnvio = new ArrayList<>();
    private DataSnapshot ultimoSnapshotConversa;
    private EditText etMensagem;
    private ImageButton btnEnviarTexto;
    private String usuarioId;
    private TextView tvStatusConexao;
    private MaterialButton btnAdminJaAtendido;

    /** true depois que o atendente confirmou "Já atendido" — bloqueia
     *  qualquer escrita posterior em "chamados/{uid}" que faria o contato
     *  voltar para a lista de atendimento. */
    private boolean serviceClosed = false;

    /** Estado atual da conexão com o Realtime Database (.info/connected). */
    private boolean conectado = false;

    // CORREÇÃO: áudio no app do atendente. A UI (microfone, lixeira,
    // cronômetro e avião) e toda a lógica abaixo são as mesmas já usadas no
    // app do cliente (MainActivity), inclusive o upload para o Cloudinary via
    // CloudinaryUploader — que fica em src/main, portanto já é compartilhado
    // pelos dois flavors. A única diferença é que a mensagem é gravada com
    // enviadaPeloAtendente = true, para o balão aparecer do lado certo nos
    // dois apps.
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
        setContentView(R.layout.activity_admin_chat); // criar esse layout, similar ao activity_main

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

        // CORREÇÃO: em vez de empurrar a tela toda para baixo com
        // fitsSystemWindows (o que deixaria uma faixa da cor de fundo do
        // tema, e não da listra verde, atrás da barra de status), o fundo
        // colorido continua se estendendo por trás da barra de status/gestos
        // — e só o CONTEÚDO de dentro (nome, botão, campo de texto) ganha um
        // respiro extra, exatamente do tamanho dessas barras do sistema.
        aplicarInsetSuperior(findViewById(R.id.topBarAdminChat));
        aplicarInsetInferior(findViewById(R.id.barraInferiorAdminChat));

        monitorarConexao();

        // >>> AQUI vai a linha que você perguntou <
        TextView tvNomeUsuarioChat = findViewById(R.id.tvNomeUsuarioChat); tvNomeUsuarioChat.setText(getIntent().getStringExtra(EXTRA_NOME_USUARIO));

        mensagensAdapter = new MensagensAdapter(listaMensagens, true); // true = app do atendente
        recyclerViewMensagens.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewMensagens.setAdapter(mensagensAdapter);

        btnEnviarTexto.setOnClickListener(v -> enviarTexto());

        // Lógica de Gravação (idêntica à do app do cliente)
        btnMicrofone.setOnClickListener(v -> pedirPermissaoEGravar());
        btnLixeiraAudio.setOnClickListener(v -> cancelarGravacao());
        btnEnviarAudio.setOnClickListener(v -> enviarAudio());

        // CORREÇÃO: "Já atendido" agora atua sobre o contato desta
        // conversa (usuarioId), sem depender de nenhuma seleção prévia na
        // tela inicial — cada contato só sai da fila quando o atendente
        // realmente entra na conversa dele e confirma.
        btnAdminJaAtendido.setOnClickListener(v -> confirmarJaAtendido());

        // CORREÇÃO: o botão de telefone agora fica sempre visível — mesmo
        // sem número cadastrado —, pois é dele que sai o modal com as
        // opções "Ligar" e "Editar número do cliente". Antes, quando o
        // cliente não tinha telefone salvo, o botão sumia (GONE) e o
        // atendente não tinha como cadastrar um número para esse contato.
        btnLigarChat.setVisibility(View.VISIBLE);
        btnLigarChat.setOnClickListener(v -> abrirOpcoesTelefone());

        // CORREÇÃO: o contador do sino nunca zerava. Na tela da lista a
        // chamada marcarComoLida(chamado) tinha sido acidentalmente engolida
        // por um comentário de uma linha só, então ninguém marcava nada como
        // lido — o número no sino só crescia. Marcar aqui, ao ABRIR a
        // conversa, é mais confiável: acontece mesmo quando o atendente entra
        // pela notificação, sem passar pela lista.
        marcarConversaComoLida();

        escutarConversa();
    }

    /**
     * CORREÇÃO ("cellular network not available for voice call"): antes, o app
     * disparava ACTION_CALL "às cegas". Agora ele (1) limpa o número e usa o
     * formato +55 quando há DDD, (2) confere modo avião / chip / sinal e avisa
     * o motivo exato em vez de deixar o sistema falhar com a mensagem
     * genérica, e (3) em aparelho com 2 chips escolhe explicitamente o chip
     * que está com sinal (o chip padrão de voz pode ser justamente o que está
     * sem serviço).
     */
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

        // Uri.fromParts já codifica o '+' corretamente para o discador.
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

    /**
     * Prepara o número para discar. Tira parênteses/espaços/hífens e, para
     * números brasileiros, usa o formato internacional (+55 DDD número).
     *
     * CORREÇÃO: antes o número era discado como digitado. Com "55..." ou
     * "51..." na frente e sem o '+', a rede lê esses dígitos como DDD (55 é
     * Santa Maria, 51 é Porto Alegre) e a chamada vira interurbana, que exige
     * código de operadora (0 + CSP) — e falha. Já o formato "+55DDDnúmero" é
     * aceito pela rede móvel para qualquer DDD, sem código de operadora.
     *
     * Regras (só dígitos, depois de limpar):
     *  - começa com '+'                      -> mantém como está
     *  - 12 ou 13 dígitos começando com 55   -> já tem o país: só põe '+'
     *  - 10 ou 11 dígitos (DDD + número)     -> põe '+55'
     *  - 8 ou 9 dígitos (sem DDD)            -> mantém (chamada local)
     *  - qualquer outro formato              -> mantém só os dígitos
     * Retorna null se não sobrar um número plausível.
     */
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

    /**
     * Em aparelho com 2 chips, escolhe a conta de chamada que está EM SERVIÇO,
     * preferindo a padrão do sistema quando ela estiver com sinal. Retorna null
     * (= deixa o sistema decidir) se não der para determinar.
     */
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

    /** Retorna a mensagem do problema encontrado, ou null se a rede parece apta a completar a chamada. */
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

    // ---------------------------------------------------------------------
    // TELEFONE DO CLIENTE (ligar / editar) — acionado pelo botão de
    // telefone (btnLigarChat) dentro da conversa.
    // ---------------------------------------------------------------------

    /**
     * Modal simples com as 2 opções pedidas: ligar para o número já
     * registrado, ou editar esse registro (para o caso de o cliente não ter
     * informado telefone no primeiro acesso do app cliente, ou o número
     * estar errado/desatualizado).
     */
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

    /**
     * CORREÇÃO/PEDIDO: pequena verificação antes de ligar. Em vez de confiar
     * cegamente no "telefoneCliente" que veio pelo Intent (snapshot da lista,
     * que pode estar desatualizado se o número acabou de ser editado, ou
     * simplesmente vazio se o cliente nunca preencheu), este método lê o
     * valor atual direto de "usuarios_dados/{usuarioId}/telefone" — a fonte
     * da verdade — e só então decide se liga ou avisa que não há número
     * cadastrado.
     */
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
                        telefoneCliente = telefone; // mantém o campo em memória sincronizado

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

    /**
     * Diálogo para o atendente cadastrar (se o cliente nunca informou) ou
     * corrigir (se estiver errado/desatualizado) o telefone do cliente.
     * Reaproveita o mesmo layout visual do diálogo já usado no app cliente
     * (layout_telefone_dialog.xml), adaptado para o admin em
     * layout_editar_telefone_admin.xml — com botão "Cancelar" e o campo já
     * pré-preenchido quando já existe um número salvo.
     */
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

    /**
     * Grava o novo telefone em "usuarios_dados/{usuarioId}/telefone" — o
     * mesmo node que o app cliente lê/escreve (fonte da verdade) — e também
     * em "chamados/{usuarioId}/telefone", que é a cópia usada pela lista do
     * atendente (Chamado.getTelefone()). Sem atualizar os dois, a lista
     * voltaria a mostrar o número antigo depois de sair e reabrir a
     * conversa.
     *
     * A escrita em "chamados" só acontece se esse chamado ainda existir —
     * mesma checagem usada em marcarConversaComoLida() — para não recriar,
     * com um updateChildren() às cegas, um registro "fantasma" (só com o
     * campo "telefone") depois que o atendimento já foi encerrado.
     */
    private void salvarTelefoneCliente(String novoTelefone) {
        if (usuarioId == null || usuarioId.isEmpty()) return;

        // CORREÇÃO: antes o setValue() era "disparar e esquecer". As regras do
        // Firebase só deixavam o próprio cliente escrever em usuarios_dados;
        // o servidor recusava a gravação do atendente (PERMISSION_DENIED) sem
        // que o app soubesse, e mesmo assim mostrava "Telefone atualizado" e
        // guardava o número novo só na memória. Na hora de ligar, o app relia
        // usuarios_dados (que continuava com o número antigo) e discava ele.
        // Agora só damos o sucesso como certo depois da confirmação do servidor.
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

    /** Mantém a cópia do telefone usada pela lista do atendente em sincronia. */
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

    // ---------------------------------------------------------------------
    // BARRAS DO SISTEMA (status bar / barra de navegação)
    // ---------------------------------------------------------------------

    /**
     * Empurra o CONTEÚDO de "view" para baixo pelo tamanho exato da barra de
     * status, mas deixa o FUNDO dela se estender por trás da barra — é o
     * mesmo efeito visual usado por apps como WhatsApp: a cor não para antes
     * da barra de status, só o texto/ícones é que respeitam essa área.
     * Alternativa ao fitsSystemWindows="true", que empurraria a View (ou a
     * tela toda) para baixo e deixaria uma faixa da cor de FUNDO DO TEMA
     * (não da listra) visível atrás da barra de status.
     */
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
            return insets; // não consome: outras Views também podem reagir aos insets
        });
    }

    /**
     * Mesma ideia de aplicarInsetSuperior(), só que para a barra de
     * navegação/gestos na parte de baixo da tela (usada na barra de envio de
     * mensagem, para o campo de texto e os botões não ficarem colados ou
     * embaixo da faixa de gestos do Android).
     */
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

        // Só atualiza se o chamado ainda existir: um updateChildren() cego
        // CRIARIA o node (com apenas "lida" e "mensagensNaoLidas") caso o
        // atendimento já tivesse sido encerrado, devolvendo o contato para a
        // lista como uma linha em branco.
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

    /**
     * CORREÇÃO: o "Já atendido" agora realmente tira o cliente da lista de
     * atendimento, e de forma definitiva. Três problemas foram corrigidos:
     *
     * 1. O node "atendimentos_pendentes/{uid}" — escrito por
     *    AssistenteIA.escalarParaAtendente() quando a IA desiste e encaminha
     *    a pessoa para o suporte humano — nunca era apagado. O contato saía
     *    da lista, mas continuava marcado como pendente no banco.
     *
     * 2. As remoções eram três chamadas soltas e sem tratamento de erro: se
     *    uma falhasse (queda de internet, regra do Firebase), o app mostrava
     *    "Contato excluído" e fechava a tela do mesmo jeito, e o contato
     *    reaparecia na lista. Agora é um único updateChildren() na raiz —
     *    atômico, tudo apaga junto ou nada apaga — e a tela só fecha depois
     *    da confirmação do servidor; em caso de falha o atendente é avisado
     *    e continua na conversa.
     *
     * 3. A flag "serviceClosed" impede que qualquer escrita atrasada
     *    (o callback do upload de áudio, por exemplo, que chega segundos
     *    depois) recrie "chamados/{uid}" logo após a exclusão, fazendo o
     *    contato ressurgir na fila sozinho.
     */
    private void excluirChamado() {
        if (usuarioId == null || usuarioId.isEmpty()) {
            Toast.makeText(this, "Contato inválido: não foi possível encerrar.", Toast.LENGTH_LONG).show();
            return;
        }

        // CORREÇÃO (causa mais provável do "botão não funciona"): os
        // callbacks de escrita do Realtime Database só disparam quando o
        // SERVIDOR confirma. Sem internet — ou com a autenticação do admin
        // ainda pendente — nem onSuccess nem onFailure chegam: o clique
        // simplesmente não produz efeito nenhum e o botão parece morto.
        // Agora a falta de conexão é detectada antes e explicada.
        if (!conectado) {
            Toast.makeText(this,
                    "Sem conexão com o servidor. Reconecte para encerrar este atendimento.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // CORREÇÃO: o app do atendente autentica sozinho em
        // AdminChamadosActivity. Se esse login não tiver concluído (ou tiver
        // falhado), as regras do Firebase negam o delete em "chamados" e o
        // botão não faz nada. Melhor avisar do que ficar em silêncio.
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
        // Some da lista de atendimento do atendente...
        remocoes.put("chamados/" + usuarioId, null);
        // ...e zera o estado da IA, permitindo um novo ciclo de atendimento
        // caso o mesmo usuário volte a escrever no futuro.
        remocoes.put("controle_ia/" + usuarioId, null);
        // Toda vez que o atendente encerra um atendimento, o aviso de que o
        // atendimento é feito por IA volta a aparecer no chat do cliente,
        // como uma mensagem "da IA" no fim da conversa. Vai dentro do mesmo
        // updateChildren() atômico: ou encerra E avisa, ou nada acontece.
        // (Não escreve em "chamados" nem mexe em mensagensNaoLidas, então o
        // contato NÃO volta para a lista do atendente.)
        String chaveAviso = FirebaseDatabase.getInstance().getReference("conversas")
                .child(usuarioId).push().getKey();
        java.util.Map<String, Object> aviso = new java.util.HashMap<>();
        aviso.put("texto", getString(R.string.aviso_atendimento_ia));
        aviso.put("enviadaPeloAtendente", true);
        aviso.put("remetente", "ia");
        aviso.put("timestamp", System.currentTimeMillis());
        remocoes.put("conversas/" + usuarioId + "/" + chaveAviso, aviso);

        // "atendimentos_pendentes" é removido à parte, de propósito: esse
        // node só ganhou regra agora (ver database.rules.json) e, se as
        // regras publicadas no console do Firebase ainda forem as antigas,
        // ele seria negado — e, dentro de um updateChildren() atômico, uma
        // negação derruba as OUTRAS remoções junto, deixando o contato preso
        // na lista. Fora do bloco, na pior das hipóteses ele falha sozinho.
        FirebaseDatabase.getInstance().getReference("atendimentos_pendentes")
                .child(usuarioId).removeValue()
                .addOnFailureListener(e -> android.util.Log.w("AdminChatActivity",
                        "Não foi possível limpar atendimentos_pendentes/" + usuarioId
                                + " — publique as regras de database.rules.json.", e));

        // Rede de segurança: se em 12s o servidor não responder nada, devolve
        // o botão ao atendente em vez de deixá-lo travado em "Encerrando...".
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

        // CORREÇÃO: o listener era anônimo e nunca removido — continuava
        // ativo depois de fechar a tela, chamando notifyDataSetChanged() num
        // RecyclerView já destruído (vazamento da Activity inteira). Agora
        // ele é guardado e desligado no onDestroy().
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
        // Áudios ainda em upload aparecem no fim da conversa, com 🕓.
        listaMensagens.addAll(audiosEmEnvio);
        mensagensAdapter.notifyDataSetChanged();
        if (!listaMensagens.isEmpty()) {
            recyclerViewMensagens.scrollToPosition(listaMensagens.size() - 1);
        }
    }

    /**
     * Única porta de escrita em "chamados/{uid}" nesta tela. Depois do
     * "Já atendido" ela não escreve mais nada — sem isso, um setValue()
     * atrasado (upload de áudio que terminou depois da exclusão, ou uma
     * escrita que estava na fila offline do Firebase) recriaria o node e o
     * contato voltaria para a lista de atendimento sozinho, agora sem nome,
     * sem telefone e sem status.
     */
    private void atualizarUltimaMensagemDoChamado(String resumo) {
        if (serviceClosed || usuarioId == null) return;
        FirebaseDatabase.getInstance().getReference("chamados")
                .child(usuarioId).child("ultimaMensagem").setValue(resumo);
    }

    private void enviarTexto() {
        String texto = etMensagem.getText().toString().trim();
        if (texto.isEmpty()) return;

        Mensagem msg = new Mensagem(texto, true); // enviadaPeloAtendente = true

        // Envia para o chat
        // A chave é gerada antes do setValue() para marcar a mensagem como
        // "enviando" (🕓); o ✅ só aparece quando o servidor confirma.
        DatabaseReference refMsg = FirebaseDatabase.getInstance().getReference("conversas")
                .child(usuarioId).push();
        final String idMsg = refMsg.getKey();
        mensagensAdapter.marcarPendente(idMsg);
        refMsg.setValue(msg)
                .addOnSuccessListener(ignorado -> mensagensAdapter.marcarEnviada(idMsg))
                .addOnFailureListener(e -> { tvStatusConexao.setText(R.string.erro_envio_mensagem); tvStatusConexao.setVisibility(View.VISIBLE); });

        // Atualiza a lista inicial para mostrar a sua resposta no centro da tela.
        // Usando as aspas tipográficas (””) conforme sua regra.
        atualizarUltimaMensagemDoChamado("“Você: ”" + texto);

        etMensagem.setText("");
    }

    // ---------------------------------------------------------------------
    // ÁUDIO (mesmo fluxo do app do cliente — ver MainActivity)
    // ---------------------------------------------------------------------

    /**
     * A permissão RECORD_AUDIO é declarada em src/main/AndroidManifest.xml,
     * que é mesclado nos dois flavors — então o app do atendente já a possui,
     * só faltava pedi-la em tempo de execução, como aqui.
     */
    private void pedirPermissaoEGravar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                    && permissaoJaFoiSolicitada) {
                // O sistema não vai mostrar o diálogo de novo (usuário marcou
                // "não perguntar novamente" ou o dispositivo já negou antes).
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

        // CHIADO (correção): três causas combinadas no código antigo.
        // 1) Taxa de 44,1 kHz: a voz só ocupa ~100 Hz-8 kHz; acima disso o app
        //    gravava apenas ruído de fundo, que é o que se ouve como chiado.
        //    16 kHz corta esse ruído na origem (padrão de mensageiros/chamadas HD).
        // 2) Fonte VOICE_COMMUNICATION: liga ganho automático e supressão de ruído
        //    do fabricante, que "bombeiam" o ruído nas pausas da fala.
        //    VOICE_RECOGNITION vem sem AGC/supressão (especificação do Android).
        // 3) Bitrate 128 kbps: exagero para voz mono; 64 kbps a 16 kHz é folgado.
        // Teste A/B em algum aparelho: trocar só a fonte por MIC ou CAMCORDER.
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioChannels(1); // voz = mono
        mediaRecorder.setAudioSamplingRate(16000);
        mediaRecorder.setAudioEncodingBitRate(64000);
        mediaRecorder.setOutputFile(audioFilePath);
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            iniciarCronometro();
        } catch (IOException | RuntimeException e) {
            // IOException vem do prepare(); RuntimeException vem do start()
            // quando o microfone está ocupado/bloqueado.
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

        // Balão provisório (🕓) enquanto o áudio sobe para o Cloudinary.
        final Mensagem provisoria = new Mensagem("", true, audioFile.getAbsolutePath(), duracao);
        provisoria.setId("local-" + System.nanoTime());
        mensagensAdapter.marcarPendente(provisoria.getId());
        audiosEmEnvio.add(provisoria);
        renderizarMensagens(ultimoSnapshotConversa);

        CloudinaryUploader.enviarAudio(audioFile, new CloudinaryUploader.UploadCallback() {
            @Override
            public void onSuccess(String urlSegura) {
                // enviadaPeloAtendente = true: o MensagensAdapter usa esse
                // campo para alinhar o balão e o app do cliente já sabe
                // reproduzir a urlAudio exatamente como faz com os áudios
                // que ele mesmo envia.
                Mensagem mensagem = new Mensagem("", true, urlSegura, duracao);
                // Sai o balão provisório; o definitivo nasce 🕓 e vira ✅ quando o Firebase confirmar.
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
                // Upload falhou: o balão provisório some (o Toast abaixo explica o motivo).
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
                // gravação muito curta / sem dados — evita crash
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
            // READ_PHONE_STATE é opcional: sem ela só pulamos a checagem de sinal.
            ligarParaCliente();
        }
    }

    /**
     * Se o atendente sair da tela no meio de uma gravação, o MediaRecorder
     * continuaria segurando o microfone do aparelho. Aqui ele é sempre
     * liberado, e o MediaPlayer do adapter também.
     */
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