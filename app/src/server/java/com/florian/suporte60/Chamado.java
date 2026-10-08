package com.florian.suporte60;

public class Chamado {
    private String id;
    private String usuarioId;
    private String nomeUsuario;
    private String ultimaMensagem;
    private String status;
    private String telefone;
    private int mensagensNaoLidas;
    private boolean lida;

    public int getMensagensNaoLidas() { return mensagensNaoLidas; }

    public Chamado() {}

    public Chamado(String id, String usuarioId, String nomeUsuario, String ultimaMensagem,
                   String status, String telefone, boolean lida) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.nomeUsuario = nomeUsuario;
        this.ultimaMensagem = ultimaMensagem;
        this.status = status;
        this.telefone = telefone;
        this.lida = lida;
    }

    public String getId() { return id; }
    public String getUsuarioId() { return usuarioId; }
    public String getNomeUsuario() { return nomeUsuario; }
    public String getUltimaMensagem() { return ultimaMensagem; }
    public String getStatus() { return status; }
    public String getTelefone() { return telefone; }
    public boolean isLida() { return lida; }
}
