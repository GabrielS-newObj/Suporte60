package com.florian.suporte60;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChamadosAdapter extends RecyclerView.Adapter<ChamadosAdapter.ChamadoViewHolder> {
    private List<Chamado> listaChamados;
    private OnChamadoClickListener listener;
    // CORREÇÃO: Usar o ID do usuário para não perder a seleção quando a lista reordenar

    public interface OnChamadoClickListener {
        void onChamadoClick(Chamado chamado);
    }

    public ChamadosAdapter(List<Chamado> listaChamados, OnChamadoClickListener listener) {
        this.listaChamados = listaChamados;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChamadoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chamado_admin, parent, false);
        return new ChamadoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChamadoViewHolder holder, int position) {
        Chamado chamado = listaChamados.get(position);

        holder.tvNomeUsuario.setText(chamado.getNomeUsuario());
        holder.tvUltimaMsg.setText(chamado.getUltimaMensagem());

        holder.ivSino.setVisibility(chamado.getMensagensNaoLidas() > 0 ? View.VISIBLE : View.GONE);
        holder.tvContadorSino.setVisibility(chamado.getMensagensNaoLidas() > 0 ? View.VISIBLE : View.GONE);
        holder.tvContadorSino.setText(String.valueOf(chamado.getMensagensNaoLidas()));



        // Clique simples: Apenas abre o chat e marca como lida
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChamadoClick(chamado);
        });


    }

    @Override
    public int getItemCount() {
        return listaChamados != null ? listaChamados.size() : 0;
    }

    static class ChamadoViewHolder extends RecyclerView.ViewHolder {
        TextView tvNomeUsuario, tvUltimaMsg, tvContadorSino;
        ImageView ivSino;

        public ChamadoViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNomeUsuario = itemView.findViewById(R.id.tvNomeUsuarioList);
            tvUltimaMsg = itemView.findViewById(R.id.tvUltimaMsgList);
            ivSino = itemView.findViewById(R.id.ivSinoNaoLido);
            tvContadorSino = itemView.findViewById(R.id.tvContadorSino);
        }
    }
}
