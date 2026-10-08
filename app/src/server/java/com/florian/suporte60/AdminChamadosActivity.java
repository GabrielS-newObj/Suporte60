package com.florian.suporte60;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AdminChamadosActivity extends AppCompatActivity {

    private boolean isKeepOnScreen = true;
    private FirebaseAuth mAuth;
    private RecyclerView recyclerViewChamados;
    private TextView tvListaVazia;
    private ChamadosAdapter chamadosAdapter;
    private List<Chamado> listaChamados = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_chamados);
        splashScreen.setKeepOnScreenCondition(() -> isKeepOnScreen);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser usuario = mAuth.getCurrentUser();
        if (usuario == null || usuario.isAnonymous()) {
            isKeepOnScreen = false;
            abrirTelaDeLogin(null);
            return;
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isKeepOnScreen) {
                isKeepOnScreen = false;
                Toast.makeText(this, "Sem resposta do Firebase. Verifique a internet do aparelho.", Toast.LENGTH_LONG).show();
            }
        }, 8000);

        verificarPermissaoDeAtendente(usuario);

        findViewById(R.id.btnSair).setOnClickListener(v -> confirmarSaida());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }

        recyclerViewChamados = findViewById(R.id.recyclerViewChamados);
        tvListaVazia = findViewById(R.id.tvListaVazia);

        aplicarInsetSuperior(findViewById(R.id.topBarAdmin));
        aplicarInsetInferior(recyclerViewChamados);

        recyclerViewChamados.setLayoutManager(new LinearLayoutManager(this));

        chamadosAdapter = new ChamadosAdapter(listaChamados, new ChamadosAdapter.OnChamadoClickListener() {

            @Override public void onChamadoClick(Chamado chamado) {

                marcarComoLida(chamado);

                Intent intent = new Intent(AdminChamadosActivity.this, AdminChatActivity.class);
                intent.putExtra(AdminChatActivity.EXTRA_USUARIO_ID, chamado.getUsuarioId());
                intent.putExtra(AdminChatActivity.EXTRA_NOME_USUARIO, chamado.getNomeUsuario());
                intent.putExtra(AdminChatActivity.EXTRA_TELEFONE, chamado.getTelefone());
                startActivity(intent);
            }


        });
        recyclerViewChamados.setAdapter(chamadosAdapter);

    }

    private void verificarPermissaoDeAtendente(FirebaseUser usuario) {
        FirebaseDatabase.getInstance().getReference("admins").child(usuario.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        isKeepOnScreen = false;
                        if (isFinishing() || isDestroyed()) return;
                        if (Boolean.TRUE.equals(snapshot.getValue())) {
                            iniciarSessaoDoAtendente();
                        } else {
                            sairParaLogin("Esta conta não tem permissão de atendente.");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        isKeepOnScreen = false;
                        android.util.Log.e("AdminChamadosActivity",
                                "Erro ao verificar permissão de atendente: " + error.getMessage(),
                                error.toException());
                        Toast.makeText(AdminChamadosActivity.this,
                                "Não foi possível verificar seu acesso: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void iniciarSessaoDoAtendente() {
        escutarChamados();
        FirebaseMessaging.getInstance().subscribeToTopic("atendentes");
    }

    private void confirmarSaida() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sair da conta?")
                .setMessage("Você precisará entrar de novo com e-mail e senha para atender.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Sair", (d, w) -> sairParaLogin(null))
                .show();
    }

    private void sairParaLogin(String mensagem) {
        removerListenerChamados();
        FirebaseMessaging.getInstance().unsubscribeFromTopic("atendentes");
        mAuth.signOut();
        abrirTelaDeLogin(mensagem);
    }

    private void abrirTelaDeLogin(String mensagem) {
        Intent intent = new Intent(this, LoginAtendenteActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (mensagem != null) {
            intent.putExtra(LoginAtendenteActivity.EXTRA_MENSAGEM, mensagem);
        }
        startActivity(intent);
        finish();
    }

    private void removerListenerChamados() {
        if (listenerChamados != null) {
            FirebaseDatabase.getInstance().getReference("chamados").removeEventListener(listenerChamados);
            listenerChamados = null;
        }
    }

    private ValueEventListener listenerChamados;

    private void escutarChamados() {
        DatabaseReference refChamados = FirebaseDatabase.getInstance().getReference("chamados");
        listenerChamados = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                listaChamados.clear();
                for (DataSnapshot chamadoSnap : snapshot.getChildren()) {
                    Chamado c = chamadoSnap.getValue(Chamado.class);
                    if (c == null) continue;

                    if (c.getId() == null || c.getId().trim().isEmpty()
                            || c.getUsuarioId() == null || c.getUsuarioId().trim().isEmpty()) {
                        chamadoSnap.getRef().removeValue();
                        continue;
                    }
                    listaChamados.add(c);
                }
                Collections.reverse(listaChamados);

                tvListaVazia.setVisibility(listaChamados.isEmpty() ? View.VISIBLE : View.GONE);
                chamadosAdapter.notifyDataSetChanged();
            }
            @Override
            public void onCancelled(DatabaseError error) {
                android.util.Log.e("AdminChamadosActivity",
                        "Erro ao ler 'chamados': " + error.getMessage(), error.toException());

                if (error.getCode() == DatabaseError.PERMISSION_DENIED
                        && mAuth.getCurrentUser() != null) {
                    sairParaLogin("Seu acesso foi revogado ou a sessão expirou. Entre novamente.");
                    return;
                }
                Toast.makeText(AdminChamadosActivity.this,
                        getString(R.string.erro_carregar_chamados) + " (" + error.getMessage() + ")",
                        Toast.LENGTH_LONG).show();
            }
        };
        refChamados.addValueEventListener(listenerChamados);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removerListenerChamados();
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

    private void marcarComoLida(Chamado chamado) {
        if (chamado == null || chamado.isLida()) return;
        FirebaseDatabase.getInstance().getReference("chamados")
                .child(chamado.getId()).child("lida").setValue(true);

        FirebaseDatabase.getInstance().getReference("chamados") .child(chamado.getId()).child("mensagensNaoLidas").setValue(0);
    }

}