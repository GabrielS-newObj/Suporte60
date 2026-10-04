package com.florian.suporte60;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Tela de login do atendente (build "server").
 *
 * Cada atendente tem a própria conta (e-mail + senha) no Firebase
 * Authentication. O Firebase guarda a sessão no aparelho, então o login só
 * é pedido uma vez — nada sensível fica dentro do APK.
 *
 * Ser atendente = existir admins/{uid} = true no Realtime Database (as
 * regras em database.rules.json usam isso). Para revogar alguém: apague
 * esse nó (efeito imediato) e, se quiser, desative a conta em
 * Authentication.
 */
public class LoginAtendenteActivity extends AppCompatActivity {

    /** Mensagem opcional exibida ao chegar aqui (ex.: acesso revogado). */
    public static final String EXTRA_MENSAGEM = "mensagem";

    private FirebaseAuth mAuth;
    private TextInputLayout tilEmail;
    private TextInputLayout tilSenha;
    private TextInputEditText etEmail;
    private TextInputEditText etSenha;
    private MaterialButton btnEntrar;
    private ProgressBar progressLogin;
    private TextView tvErroLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Tela de fundo claro com cores fixas: evita texto claro sobre fundo
        // claro quando o aparelho está no modo escuro.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // Já existe sessão de atendente (ex.: o app foi recriado no meio do
        // login)? Segue para a fila, que revalida a permissão. Quando vimos
        // aqui com uma mensagem (sessão acabou de ser encerrada), não
        // redireciona — evita qualquer chance de laço.
        FirebaseUser atual = mAuth.getCurrentUser();
        if (atual != null && !atual.isAnonymous()
                && getIntent().getStringExtra(EXTRA_MENSAGEM) == null) {
            irParaFila();
            return;
        }

        setContentView(R.layout.activity_login_atendente);

        tilEmail = findViewById(R.id.tilEmail);
        tilSenha = findViewById(R.id.tilSenha);
        etEmail = findViewById(R.id.etEmail);
        etSenha = findViewById(R.id.etSenha);
        btnEntrar = findViewById(R.id.btnEntrar);
        progressLogin = findViewById(R.id.progressLogin);
        tvErroLogin = findViewById(R.id.tvErroLogin);

        aplicarInsets(findViewById(R.id.rootLogin));

        String mensagem = getIntent().getStringExtra(EXTRA_MENSAGEM);
        if (mensagem != null) {
            mostrarErro(mensagem);
        }

        btnEntrar.setOnClickListener(v -> tentarLogin());
        etSenha.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tentarLogin();
                return true;
            }
            return false;
        });
    }

    private void tentarLogin() {
        tilEmail.setError(null);
        tilSenha.setError(null);
        tvErroLogin.setVisibility(View.GONE);

        String email = etEmail.getText() == null ? "" : etEmail.getText().toString().trim();
        String senha = etSenha.getText() == null ? "" : etSenha.getText().toString();

        boolean valido = true;
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Digite um e-mail válido.");
            valido = false;
        }
        if (senha.isEmpty()) {
            tilSenha.setError("Digite a senha.");
            valido = false;
        }
        if (!valido) return;

        definirCarregando(true);
        mAuth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener(this, task -> {
                    FirebaseUser usuario = mAuth.getCurrentUser();
                    if (task.isSuccessful() && usuario != null) {
                        verificarPermissao(usuario);
                    } else {
                        definirCarregando(false);
                        mostrarErro(traduzirErro(task.getException()));
                    }
                });
    }

    /** Confere admins/{uid} para dar o retorno logo aqui, no botão Entrar. */
    private void verificarPermissao(FirebaseUser usuario) {
        FirebaseDatabase.getInstance().getReference("admins").child(usuario.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (Boolean.TRUE.equals(snapshot.getValue())) {
                            irParaFila();
                        } else {
                            mAuth.signOut();
                            definirCarregando(false);
                            mostrarErro("Esta conta não tem permissão de atendente.");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        android.util.Log.e("LoginAtendente",
                                "Erro ao verificar permissão: " + error.getMessage(),
                                error.toException());
                        mAuth.signOut();
                        definirCarregando(false);
                        mostrarErro("Não foi possível verificar seu acesso: " + error.getMessage());
                    }
                });
    }

    private void irParaFila() {
        Intent intent = new Intent(this, AdminChamadosActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private String traduzirErro(Exception e) {
        if (e instanceof FirebaseNetworkException) {
            return "Sem conexão com a internet. Verifique e tente de novo.";
        }
        if (e instanceof FirebaseTooManyRequestsException) {
            return "Muitas tentativas. Aguarde alguns minutos e tente de novo.";
        }
        if (e instanceof FirebaseAuthInvalidUserException) {
            String codigo = ((FirebaseAuthInvalidUserException) e).getErrorCode();
            if ("ERROR_USER_DISABLED".equals(codigo)) {
                return "Esta conta foi desativada.";
            }
            return "E-mail ou senha incorretos.";
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return "E-mail ou senha incorretos.";
        }
        android.util.Log.e("LoginAtendente", "Falha no login", e);
        return "Não foi possível entrar. Tente novamente.";
    }

    private void mostrarErro(String texto) {
        tvErroLogin.setText(texto);
        tvErroLogin.setVisibility(View.VISIBLE);
    }

    private void definirCarregando(boolean carregando) {
        btnEntrar.setEnabled(!carregando);
        etEmail.setEnabled(!carregando);
        etSenha.setEnabled(!carregando);
        progressLogin.setVisibility(carregando ? View.VISIBLE : View.GONE);
    }

    /** Respeita barras do sistema e teclado (o app é edge-to-edge). */
    private void aplicarInsets(View root) {
        final int esquerdo = root.getPaddingLeft();
        final int topo = root.getPaddingTop();
        final int direito = root.getPaddingRight();
        final int base = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets barras = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(esquerdo + barras.left, topo + barras.top,
                    direito + barras.right, base + barras.bottom);
            return insets;
        });
    }
}
