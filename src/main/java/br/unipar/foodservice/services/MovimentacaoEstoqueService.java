package br.unipar.foodservice.services;

import br.unipar.foodservice.dtos.MovimentacaoEstoqueCreateRequest;
import br.unipar.foodservice.entities.Insumo;
import br.unipar.foodservice.entities.Lote;
import br.unipar.foodservice.entities.MovimentacaoEstoque;
import br.unipar.foodservice.entities.UnidadeMedida;
import br.unipar.foodservice.enums.TipoMovimentacao;
import br.unipar.foodservice.exceptions.BusinessException;
import br.unipar.foodservice.exceptions.InvalidRequestException;
import br.unipar.foodservice.repositories.InsumoRepository;
import br.unipar.foodservice.repositories.LoteRepository;
import br.unipar.foodservice.repositories.MovimentacaoEstoqueRepository;
import br.unipar.foodservice.repositories.UnidadeMedidaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Núcleo da movimentação de estoque. Implementa:
 *   - Conversão automática para a unidadePadrao do insumo.
 *   - Lógica FEFO (First Expire First Out) em saídas sem lote específico.
 *   - Splitting: uma saída que toca N lotes gera N registros de MovimentacaoEstoque.
 *   - Bloqueio de SAIDA_VENDA via API manual (gerada apenas pelo fechamento de comanda).
 *   - AJUSTE_INVENTARIO substituindo o saldo do lote indicado (gera ENTRADA ou SAIDA).
 */
@Service
@RequiredArgsConstructor
public class MovimentacaoEstoqueService {

    private final MovimentacaoEstoqueRepository repository;
    private final InsumoRepository insumoRepository;
    private final LoteRepository loteRepository;
    private final UnidadeMedidaRepository unidadeRepository;
    private final UnidadeMedidaService unidadeService;
    private final Clock clock;

    @Transactional
    public List<MovimentacaoEstoque> registrar(MovimentacaoEstoqueCreateRequest req) {
        if (req.tipo() == TipoMovimentacao.SAIDA_VENDA) {
            throw new BusinessException("SAIDA_VENDA não pode ser registrada manualmente. " +
                    "É gerada automaticamente pelo fechamento de comanda.");
        }

        Insumo insumo = insumoRepository.findById(req.insumoId())
                .orElseThrow(() -> new InvalidRequestException("Insumo não encontrado: " + req.insumoId()));
        UnidadeMedida unidade = unidadeRepository.findById(req.unidadeId())
                .orElseThrow(() -> new InvalidRequestException("UnidadeMedida não encontrada: " + req.unidadeId()));
        validarCompatibilidade(insumo, unidade);

        BigDecimal qtdNaPadrao = unidadeService.converter(req.quantidade(), unidade, insumo.getUnidadePadrao());

        return switch (req.tipo()) {
            case ENTRADA_COMPRA, ENTRADA_TROCA -> List.of(processarEntrada(req, insumo, unidade, qtdNaPadrao));
            case SAIDA_PERDA_VALIDADE, SAIDA_PERDA_QUEBRA -> processarSaida(req, insumo, unidade, qtdNaPadrao);
            case AJUSTE_INVENTARIO -> List.of(processarAjuste(req, insumo, unidade, qtdNaPadrao));
            case SAIDA_VENDA -> throw new IllegalStateException("Não alcançável.");
        };
    }

    @Transactional(readOnly = true)
    public List<MovimentacaoEstoque> listarPorInsumo(Long insumoId) {
        return repository.findByInsumoIdOrderByDataHoraDesc(insumoId);
    }

    /**
     * Registra uma {@code SAIDA_VENDA} — só pode ser chamado pelo
     * fechamento de comanda ({@code ComandaService.fechar}). Bypassa a
     * validação do {@link #registrar} (que recusa SAIDA_VENDA manual) mas
     * mantém toda a lógica de FEFO/split entre lotes.
     *
     * @param insumoId         Insumo a baixar (vindo de {@code Produto.insumo} para UNITARIO).
     * @param unidadeId        Unidade da quantidade informada — normalmente
     *                         a {@code unidadePadrao} do insumo.
     * @param quantidade       Quantidade na unidade informada (positiva).
     * @param justificativa    Texto livre (ex.: "SAIDA_VENDA — comanda M-12-001 — item #42").
     */
    @Transactional
    public void registrarSaidaVenda(Long insumoId,
                                    Long unidadeId,
                                    BigDecimal quantidade,
                                    String justificativa) {
        Insumo insumo = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new InvalidRequestException("Insumo não encontrado: " + insumoId));
        UnidadeMedida unidade = unidadeRepository.findById(unidadeId)
                .orElseThrow(() -> new InvalidRequestException("UnidadeMedida não encontrada: " + unidadeId));
        validarCompatibilidade(insumo, unidade);

        BigDecimal qtdNaPadrao = unidadeService.converter(quantidade, unidade, insumo.getUnidadePadrao());

        var sintetico = new MovimentacaoEstoqueCreateRequest(
                TipoMovimentacao.SAIDA_VENDA,
                insumoId,
                null,            // loteId — null força FEFO
                unidadeId,
                quantidade,
                null,            // custoUnitario (n/a em saída)
                null,            // validade (n/a)
                null,            // codigoLote (n/a)
                justificativa);
        processarSaida(sintetico, insumo, unidade, qtdNaPadrao);
    }

    /** ENTRADA_COMPRA / ENTRADA_TROCA — cria novo Lote (se loteId nulo) ou soma a um existente. */
    private MovimentacaoEstoque processarEntrada(MovimentacaoEstoqueCreateRequest req,
                                                 Insumo insumo,
                                                 UnidadeMedida unidade,
                                                 BigDecimal qtdNaPadrao) {
        Lote lote;
        if (req.loteId() != null) {
            lote = loteRepository.findById(req.loteId())
                    .orElseThrow(() -> new InvalidRequestException("Lote não encontrado: " + req.loteId()));
            if (!lote.getInsumo().getId().equals(insumo.getId())) {
                throw new BusinessException("Lote informado não pertence ao insumo " + insumo.getNome() + ".");
            }
            lote.setQuantidadeRestante(lote.getQuantidadeRestante().add(qtdNaPadrao));
            lote.setQuantidadeInicial(lote.getQuantidadeInicial().add(qtdNaPadrao));
        } else {
            if (req.validade() == null) {
                throw new BusinessException("validade é obrigatória ao criar um novo lote em uma entrada.");
            }
            if (req.validade().isBefore(LocalDate.now(clock))) {
                throw new BusinessException("Validade do lote não pode estar no passado.");
            }
            lote = Lote.builder()
                    .insumo(insumo)
                    .codigo(req.codigoLote())
                    .validade(req.validade())
                    .quantidadeInicial(qtdNaPadrao)
                    .quantidadeRestante(qtdNaPadrao)
                    .custoUnitario(req.custoUnitario() == null ? BigDecimal.ZERO : req.custoUnitario())
                    .ativo(true)
                    .build();
            lote = loteRepository.save(lote);
        }
        return salvarMovimentacao(req.tipo(), insumo, lote, unidade, req.quantidade(),
                qtdNaPadrao, req.custoUnitario(), req.justificativa());
    }

    /** Saídas com FEFO ou lote específico. Retorna 1..N registros conforme split. */
    private List<MovimentacaoEstoque> processarSaida(MovimentacaoEstoqueCreateRequest req,
                                                     Insumo insumo,
                                                     UnidadeMedida unidade,
                                                     BigDecimal qtdNaPadrao) {
        List<MovimentacaoEstoque> registros = new ArrayList<>();

        if (req.loteId() != null) {
            Lote lote = loteRepository.findById(req.loteId())
                    .orElseThrow(() -> new InvalidRequestException("Lote não encontrado: " + req.loteId()));
            if (!lote.getInsumo().getId().equals(insumo.getId())) {
                throw new BusinessException("Lote informado não pertence ao insumo.");
            }
            if (lote.getQuantidadeRestante().compareTo(qtdNaPadrao) < 0) {
                throw new BusinessException("Saldo insuficiente no lote " + lote.getId()
                        + ": disponível " + lote.getQuantidadeRestante() + ", requisitado " + qtdNaPadrao + ".");
            }
            lote.setQuantidadeRestante(lote.getQuantidadeRestante().subtract(qtdNaPadrao));
            registros.add(salvarMovimentacao(req.tipo(), insumo, lote, unidade,
                    req.quantidade(), qtdNaPadrao, null, req.justificativa()));
            return registros;
        }

        // FEFO: consome dos lotes ordenados por validade ASC.
        BigDecimal restante = qtdNaPadrao;
        List<Lote> fefo = loteRepository.findFefo(insumo.getId());
        for (Lote lote : fefo) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal consumir = restante.min(lote.getQuantidadeRestante());
            lote.setQuantidadeRestante(lote.getQuantidadeRestante().subtract(consumir));
            registros.add(salvarMovimentacao(req.tipo(), insumo, lote, unidade,
                    converterParaUnidadeOriginal(consumir, insumo, unidade),
                    consumir, null, req.justificativa()));
            restante = restante.subtract(consumir);
        }
        if (restante.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Saldo insuficiente do insumo " + insumo.getNome()
                    + ": faltam " + restante + " " + insumo.getUnidadePadrao().getSimbolo() + ".");
        }
        return registros;
    }

    /**
     * AJUSTE_INVENTARIO substitui o saldo do lote pela quantidade informada (em unidadePadrao
     * já calculada). Gera uma movimentação cuja `quantidade` é a diferença (positiva ou negativa
     * em módulo) — registramos sempre como módulo positivo, e o tipo continua AJUSTE_INVENTARIO.
     */
    private MovimentacaoEstoque processarAjuste(MovimentacaoEstoqueCreateRequest req,
                                                Insumo insumo,
                                                UnidadeMedida unidade,
                                                BigDecimal qtdNaPadrao) {
        if (req.loteId() == null) {
            throw new BusinessException("loteId é obrigatório em AJUSTE_INVENTARIO.");
        }
        Lote lote = loteRepository.findById(req.loteId())
                .orElseThrow(() -> new InvalidRequestException("Lote não encontrado: " + req.loteId()));
        if (!lote.getInsumo().getId().equals(insumo.getId())) {
            throw new BusinessException("Lote informado não pertence ao insumo.");
        }
        BigDecimal diferenca = qtdNaPadrao.subtract(lote.getQuantidadeRestante());
        lote.setQuantidadeRestante(qtdNaPadrao);
        return salvarMovimentacao(TipoMovimentacao.AJUSTE_INVENTARIO, insumo, lote, unidade,
                req.quantidade(), diferenca.abs(), null,
                "Ajuste de inventário: saldo passou de " + lote.getQuantidadeRestante().add(diferenca.negate())
                        + " para " + qtdNaPadrao + ". " + (req.justificativa() == null ? "" : req.justificativa()));
    }

    private MovimentacaoEstoque salvarMovimentacao(TipoMovimentacao tipo,
                                                   Insumo insumo,
                                                   Lote lote,
                                                   UnidadeMedida unidade,
                                                   BigDecimal qtdInformada,
                                                   BigDecimal qtdNaPadrao,
                                                   BigDecimal custo,
                                                   String justificativa) {
        MovimentacaoEstoque mov = MovimentacaoEstoque.builder()
                .tipo(tipo)
                .insumo(insumo)
                .lote(lote)
                .unidade(unidade)
                .quantidade(qtdInformada)
                .quantidadeUnidadePadrao(qtdNaPadrao)
                .custoUnitario(custo)
                .justificativa(justificativa)
                .dataHora(LocalDateTime.now(clock))
                .responsavel(usuarioCorrente())
                .build();
        return repository.save(mov);
    }

    private void validarCompatibilidade(Insumo insumo, UnidadeMedida unidade) {
        if (insumo.getUnidadePadrao().getTipoMedida() != unidade.getTipoMedida()) {
            throw new BusinessException("Unidade " + unidade.getSimbolo()
                    + " (" + unidade.getTipoMedida() + ") não converte para a unidade padrão do insumo "
                    + insumo.getNome() + " (" + insumo.getUnidadePadrao().getSimbolo() + ").");
        }
    }

    private BigDecimal converterParaUnidadeOriginal(BigDecimal qtdNaPadrao, Insumo insumo, UnidadeMedida unidadeOriginal) {
        return unidadeService.converter(qtdNaPadrao, insumo.getUnidadePadrao(), unidadeOriginal);
    }

    private String usuarioCorrente() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
