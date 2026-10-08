package com.florian.suporte60;

import com.google.firebase.database.Exclude;

public class Mensagem {
    private String texto;
    private boolean enviadaPeloAtendente;
    private String urlAudio;
    private String duracaoAudio;
    private String remetente;
    private long timestamp;

    private String id;

    public Mensagem() {}

    public Mensagem(String texto, boolean enviadaPeloAtendente) {
        this.texto = texto;
        this.enviadaPeloAtendente = enviadaPeloAtendente;
        this.urlAudio = "";
        this.duracaoAudio = "";
    }

    public Mensagem(String texto, boolean enviadaPeloAtendente, String urlAudio, String duracaoAudio) {
        this.texto = texto;
        this.enviadaPeloAtendente = enviadaPeloAtendente;
        this.urlAudio = urlAudio;
        this.duracaoAudio = duracaoAudio;
    }

    public Mensagem(String texto, String remetente, long timestamp) {
        this.texto = texto;
        this.remetente = remetente;
        this.timestamp = timestamp;
        this.enviadaPeloAtendente = "ia".equals(remetente) || "atendente".equals(remetente);
    }

    @Exclude
    public String getId() {
        return id;
    }

    @Exclude
    public void setId(String id) {
        this.id = id;
    }

    public boolean isEnviadaPeloAtendente() {
        return enviadaPeloAtendente;
    }

    public void setEnviadaPeloAtendente(boolean enviadaPeloAtendente) {
        this.enviadaPeloAtendente = enviadaPeloAtendente;
    }

    public String getUrlAudio() {
        return urlAudio;
    }

    public void setUrlAudio(String urlAudio) {
        this.urlAudio = urlAudio;
    }

    public String getDuracaoAudio() {
        return duracaoAudio;
    }

    public void setDuracaoAudio(String duracaoAudio) {
        this.duracaoAudio = duracaoAudio;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public String getRemetente() {
        return remetente;
    }

    public void setRemetente(String remetente) {
        this.remetente = remetente;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}