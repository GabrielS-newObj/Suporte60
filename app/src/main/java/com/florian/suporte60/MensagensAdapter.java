package com.florian.suporte60;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.Toast;
import android.media.MediaPlayer;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MensagensAdapter extends RecyclerView.Adapter<MensagensAdapter.MensagemViewHolder> {
    private List<Mensagem> listaMensagens;
    private final boolean attendantMode;
    private MediaPlayer mediaPlayer;
    private int posicaoTocando = -1;
    private Handler handler = new Handler();
    private Runnable runnableTempo;
    private String urlAudioAtual = null;
    private ImageButton botaoAudioAtual = null;
    private boolean isPausado = false;
    private final Set<String> idsPendentes = new HashSet<>();

    public MensagensAdapter(List<Mensagem> listaMensagens, boolean attendantMode) { this.listaMensagens = listaMensagens; this.attendantMode = attendantMode; }

    @NonNull
    @Override
    public MensagemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MensagemViewHolder(view);
    }


    @Override
    public void onBindViewHolder(@NonNull MensagemViewHolder holder, int position) {
        Mensagem mensagem = listaMensagens.get(position);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.containerBalao.getLayoutParams();

        boolean isTocandoAgora = (position == posicaoTocando && mediaPlayer != null && mediaPlayer.isPlaying());

        boolean isMensagemMinha;

        if ("ia".equals(mensagem.getRemetente()) || "atendente".equals(mensagem.getRemetente()) || mensagem.isEnviadaPeloAtendente()) {
            isMensagemMinha = false;
        } else {
            isMensagemMinha = true;
        }

        if (isMensagemMinha) {
            params.gravity = Gravity.END;
            holder.containerBalao.setBackgroundResource(R.drawable.bg_balao_usuario);
            holder.tvTextoMensagem.setTextColor(0xFFFFFFFF);
        } else {
            params.gravity = Gravity.START;
            holder.containerBalao.setBackgroundResource(R.drawable.bg_balao_mensagem);
            holder.tvTextoMensagem.setTextColor(0xFF000000);
        }

        holder.containerBalao.setLayoutParams(params);

        boolean isMensagemDeAudio = mensagem.getUrlAudio() != null && !mensagem.getUrlAudio().trim().isEmpty();

        if (!isMensagemDeAudio) {
            holder.tvTextoMensagem.setVisibility(View.VISIBLE);
            holder.layoutAudioPlayer.setVisibility(View.GONE);
            holder.tvDuracaoAudio.setVisibility(View.GONE);
            holder.tvTextoMensagem.setText(mensagem.getTexto());
        }
        else {
            holder.tvTextoMensagem.setVisibility(View.GONE);
            holder.layoutAudioPlayer.setVisibility(View.VISIBLE);
            holder.tvDuracaoAudio.setVisibility(View.VISIBLE);
            holder.tvDuracaoAudio.setText(mensagem.getDuracaoAudio());
            holder.btnPlayPause.setImageResource(
                    isTocandoAgora ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }

        holder.btnPlayPause.setOnClickListener(v -> tocarAudio(mensagem.getUrlAudio(), position, holder));

        boolean enviadaPorMim = attendantMode
                ? (mensagem.isEnviadaPeloAtendente() && !"ia".equals(mensagem.getRemetente()))
                : isMensagemMinha;
        if (!enviadaPorMim) {
            holder.tvCheckEnvio.setVisibility(View.GONE);
        } else {
            boolean pendente = mensagem.getId() != null && idsPendentes.contains(mensagem.getId());
            holder.tvCheckEnvio.setVisibility(View.VISIBLE);
            holder.tvCheckEnvio.setText(pendente ? "🕓" : "✅");
            holder.tvCheckEnvio.setContentDescription(pendente ? "Enviando" : "Enviada");
        }
    }

    public void marcarPendente(String id) {
        if (id != null) idsPendentes.add(id);
    }

    public void marcarEnviada(String id) {
        if (id == null || !idsPendentes.remove(id)) return;
        for (int i = 0; i < listaMensagens.size(); i++) {
            if (id.equals(listaMensagens.get(i).getId())) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void esquecerPendente(String id) {
        if (id != null) idsPendentes.remove(id);
    }

    private void tocarAudio(String urlAudio, int position, MensagemViewHolder holder) {
        if (urlAudio == null || urlAudio.trim().isEmpty()) {
            Toast.makeText(holder.itemView.getContext(), "URL de áudio inválida ou inexistente.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (urlAudio.equals(urlAudioAtual) && mediaPlayer != null && isPausado) {
            try {
                mediaPlayer.start();
                isPausado = false;
                posicaoTocando = position;
                holder.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                atualizarSeekBar(holder);
            } catch (Exception e) {
                Log.e("AudioPlayer", "Erro ao retomar áudio", e);
            }
            return;
        }

        if (urlAudio.equals(urlAudioAtual) && mediaPlayer != null && mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.pause();
                isPausado = true;
                holder.btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                pararAtualizacaoSeekBar();
            } catch (Exception e) {
                Log.e("AudioPlayer", "Erro ao pausar áudio", e);
            }
            return;
        }

        pararERecomporPlayer();

        try {
            urlAudioAtual = urlAudio;
            botaoAudioAtual = holder.btnPlayPause;
            posicaoTocando = position;

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());

            mediaPlayer.setDataSource(urlAudio);

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPausado = false;
                if (botaoAudioAtual != null) {
                    botaoAudioAtual.setImageResource(android.R.drawable.ic_media_pause);
                }
                holder.seekBarAudio.setMax(mp.getDuration());
                atualizarSeekBar(holder);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                pararAtualizacaoSeekBar();
                holder.seekBarAudio.setProgress(0);
                resetarEstadoPlayer();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e("AudioPlayer", "Erro no MediaPlayer (stream): what=" + what + ", extra=" + extra);
                Toast.makeText(holder.itemView.getContext(), "Falha ao reproduzir o áudio da nuvem.", Toast.LENGTH_SHORT).show();
                pararAtualizacaoSeekBar();
                resetarEstadoPlayer();
                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e("AudioPlayer", "Exceção ao configurar MediaPlayer", e);
            Toast.makeText(holder.itemView.getContext(), "Erro ao inicializar reprodutor.", Toast.LENGTH_SHORT).show();
            resetarEstadoPlayer();
        }
    }

    private void resetarEstadoPlayer() {
        if (botaoAudioAtual != null) {
            botaoAudioAtual.setImageResource(android.R.drawable.ic_media_play);
        }
        posicaoTocando = -1;
        pararERecomporPlayer();
    }

    private void pararERecomporPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e("AudioPlayer", "Erro ao liberar MediaPlayer", e);
            }
            mediaPlayer = null;
        }
        urlAudioAtual = null;
        botaoAudioAtual = null;
        isPausado = false;
    }

    private void atualizarSeekBar(MensagemViewHolder holder) {
        runnableTempo = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    holder.seekBarAudio.setProgress(mediaPlayer.getCurrentPosition());
                    handler.postDelayed(this, 100);
                }
            }
        };
        handler.post(runnableTempo);
    }

    private void pararAtualizacaoSeekBar() {
        if (runnableTempo != null) {
            handler.removeCallbacks(runnableTempo);
        }
    }

    @Override
    public int getItemCount() {
        return listaMensagens != null ? listaMensagens.size() : 0;
    }

    public void liberarRecursos() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
            pararAtualizacaoSeekBar();
        }
    }

    static class MensagemViewHolder extends RecyclerView.ViewHolder {
        LinearLayout containerBalao, layoutAudioPlayer;
        TextView tvTextoMensagem, tvDuracaoAudio, tvCheckEnvio;
        ImageButton btnPlayPause;
        SeekBar seekBarAudio;
        public MensagemViewHolder(@NonNull View itemView) {
            super(itemView);
            containerBalao = itemView.findViewById(R.id.containerBalao);
            tvTextoMensagem = itemView.findViewById(R.id.tvTextoMensagem);
            layoutAudioPlayer = itemView.findViewById(R.id.layoutAudioPlayer);
            btnPlayPause = itemView.findViewById(R.id.btnPlayPause);
            seekBarAudio = itemView.findViewById(R.id.seekBarAudio);
            tvDuracaoAudio = itemView.findViewById(R.id.tvDuracaoAudio);
            tvCheckEnvio = itemView.findViewById(R.id.tvCheckEnvio);
        }
    }}