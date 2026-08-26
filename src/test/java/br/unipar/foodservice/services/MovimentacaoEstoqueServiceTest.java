package br.unipar.foodservice.services;

import br.unipar.foodservice.dtos.MovimentacaoEstoqueCreateRequest;
import br.unipar.foodservice.entities.Insumo;
import br.unipar.foodservice.entities.Lote;
import br.unipar.foodservice.entities.MovimentacaoEstoque;
import br.unipar.foodservice.entities.UnidadeMedida;
import br.unipar.foodservice.enums.TipoMedida;
import br.unipar.foodservice.enums.TipoMovimentacao;
import br.unipar.foodservice.exceptions.BusinessException;
import br.unipar.foodservice.repositories.InsumoRepository;
import br.unipar.foodservice.repositories.LoteRepository;
import br.unipar.foodservice.repositories.MovimentacaoEstoqueRepository;
import br.unipar.foodservice.repositories.UnidadeMedidaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovimentacaoEstoqueServiceTest {

    private static final Clock CLOCK_SAO_PAULO = Clock.fixed(
            Instant.parse("2026-08-25T01:10:00Z"),
            ZoneId.of("America/Sao_Paulo"));

    @Mock private MovimentacaoEstoqueRepository repository;
    @Mock private InsumoRepository insumoRepository;
    @Mock private LoteRepository loteRepository;
    @Mock private UnidadeMedidaRepository unidadeRepository;
    @Mock private UnidadeMedidaService unidadeService;

    private MovimentacaoEstoqueService service;

    @BeforeEach
    void setUp() {
        service = new MovimentacaoEstoqueService(
                repository,
                insumoRepository,
                loteRepository,
                unidadeRepository,
                unidadeService,
                CLOCK_SAO_PAULO);
    }

    private UnidadeMedida grama() {
        return UnidadeMedida.builder().id(1L).simbolo("g").nome("Grama")
                .tipoMedida(TipoMedida.MASSA).fatorParaBase(BigDecimal.ONE).ativo(true).build();
    }

    private Insumo farinha() {
        return Insumo.builder().id(10L).nome("Farinha de trigo")
                .unidadePadrao(grama()).estoqueMinimo(BigDecimal.ZERO).ativo(true).build();
    }

    @Test
    void saidaVendaManual_deveSerBloqueada() {
        var req = new MovimentacaoEstoqueCreateRequest(
                TipoMovimentacao.SAIDA_VENDA, 10L, null, 1L,
                BigDecimal.ONE, null, null, null, null);

        assertThatThrownBy(() -> service.registrar(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SAIDA_VENDA");
    }

    @Test
    void saidaPerdaPorValidade_comFefo_deveConsumirLoteMaisAntigoPrimeiro() {
        UnidadeMedida g = grama();
        Insumo insumo = farinha();

        Lote loteAntigo = Lote.builder().id(100L).insumo(insumo).validade(LocalDate.now().plusDays(10))
                .quantidadeRestante(new BigDecimal("300")).quantidadeInicial(new BigDecimal("300"))
                .custoUnitario(BigDecimal.ZERO).ativo(true).build();
        Lote loteNovo = Lote.builder().id(101L).insumo(insumo).validade(LocalDate.now().plusDays(60))
                .quantidadeRestante(new BigDecimal("500")).quantidadeInicial(new BigDecimal("500"))
                .custoUnitario(BigDecimal.ZERO).ativo(true).build();

        when(insumoRepository.findById(10L)).thenReturn(Optional.of(insumo));
        when(unidadeRepository.findById(1L)).thenReturn(Optional.of(g));
        when(unidadeService.converter(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0)); // como tudo já está em g, retorna a própria qtd
        when(loteRepository.findFefo(10L)).thenReturn(List.of(loteAntigo, loteNovo));
        when(repository.save(any(MovimentacaoEstoque.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new MovimentacaoEstoqueCreateRequest(
                TipoMovimentacao.SAIDA_PERDA_VALIDADE, 10L, null, 1L,
                new BigDecimal("400"), null, null, null, "validade vencendo");

        List<MovimentacaoEstoque> resultado = service.registrar(req);

        // Esperado: 300 do lote antigo (esgotado) + 100 do novo = 2 movimentações.
        assertThat(resultado).hasSize(2);
        assertThat(loteAntigo.getQuantidadeRestante()).isEqualByComparingTo("0");
        assertThat(loteNovo.getQuantidadeRestante()).isEqualByComparingTo("400");
    }

    @Test
    void saida_semSaldoSuficiente_deveLancar() {
        UnidadeMedida g = grama();
        Insumo insumo = farinha();
        Lote unico = Lote.builder().id(200L).insumo(insumo).validade(LocalDate.now().plusDays(5))
                .quantidadeRestante(new BigDecimal("50")).quantidadeInicial(new BigDecimal("50"))
                .custoUnitario(BigDecimal.ZERO).ativo(true).build();

        when(insumoRepository.findById(10L)).thenReturn(Optional.of(insumo));
        when(unidadeRepository.findById(1L)).thenReturn(Optional.of(g));
        when(unidadeService.converter(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(loteRepository.findFefo(10L)).thenReturn(List.of(unico));

        var req = new MovimentacaoEstoqueCreateRequest(
                TipoMovimentacao.SAIDA_PERDA_QUEBRA, 10L, null, 1L,
                new BigDecimal("100"), null, null, null, null);

        assertThatThrownBy(() -> service.registrar(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Saldo insuficiente");
    }

    @Test
    void movimentacao_deveRegistrarDataHoraNoTimezoneConfigurado() {
        UnidadeMedida g = grama();
        Insumo insumo = farinha();
        Lote lote = Lote.builder().id(300L).insumo(insumo)
                .validade(LocalDate.now(CLOCK_SAO_PAULO).plusDays(10))
                .quantidadeRestante(new BigDecimal("10"))
                .quantidadeInicial(new BigDecimal("10"))
                .custoUnitario(BigDecimal.ZERO).ativo(true).build();

        when(insumoRepository.findById(10L)).thenReturn(Optional.of(insumo));
        when(unidadeRepository.findById(1L)).thenReturn(Optional.of(g));
        when(unidadeService.converter(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(loteRepository.findById(300L)).thenReturn(Optional.of(lote));
        when(repository.save(any(MovimentacaoEstoque.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new MovimentacaoEstoqueCreateRequest(
                TipoMovimentacao.SAIDA_PERDA_QUEBRA, 10L, 300L, 1L,
                BigDecimal.ONE, null, null, null, "teste de timezone");

        MovimentacaoEstoque resultado = service.registrar(req).getFirst();

        assertThat(resultado.getDataHora())
                .isEqualTo(LocalDateTime.of(2026, 8, 24, 22, 10));
    }
}
