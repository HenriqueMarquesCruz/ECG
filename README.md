# Projeto de ECG — Instrumentação Biomédica

Projeto desenvolvido para a disciplina **EEE025 — Instrumentação Biomédica**, do curso de Engenharia Elétrica da **Universidade Federal de Minas Gerais (UFMG)**.

**Professor:** [Renan Fernandes Kozan](https://virtual.ufmg.br/20262/user/view.php?id=182005&course=11008)

---

## Sobre o projeto

Este projeto tem como objetivo o desenvolvimento de um sistema para **aquisição e visualização do sinal eletrocardiográfico (ECG)**, aplicando conceitos de instrumentação biomédica, aquisição de biopotenciais, condicionamento de sinais e processamento de sinais.

O eletrocardiograma é um método não invasivo utilizado para registrar a atividade elétrica do coração por meio de eletrodos posicionados sobre a superfície corporal. O sinal obtido apresenta amplitudes relativamente baixas e é suscetível a diferentes fontes de interferência e ruído, tornando necessário o desenvolvimento de um sistema adequado de aquisição e condicionamento.

O projeto busca implementar as principais etapas envolvidas na obtenção do sinal de ECG, desde a captação do biopotencial até sua disponibilização para análise.

---

## Objetivo

Desenvolver um sistema de instrumentação capaz de adquirir, condicionar e visualizar um sinal de ECG, aplicando conceitos fundamentais de instrumentação biomédica.

---

## Arquitetura do sistema

De maneira geral, o sistema é composto pelas seguintes etapas:

```text
Eletrodos
   │
   ▼
Aquisição do sinal de ECG
   │
   ▼
Amplificador de instrumentação
   │
   ▼
Filtragem
   │
   ▼
Condicionamento do sinal
   │
   ▼
Conversão A/D (ESP32)
   │
   ▼
Processamento digital ((ESP32)
   │
   ▼
Visualização do ECG
```

Cada etapa possui uma função específica para garantir que o sinal cardíaco seja adquirido com amplitude e qualidade suficientes para posterior análise.

---

## ECG

O eletrocardiograma representa a diferença de potencial elétrico medida na superfície do corpo em decorrência da atividade elétrica cardíaca.

Entre os principais componentes observados no sinal estão:

* **Onda P:** associada à despolarização dos átrios;
* **Complexo QRS:** associado à despolarização dos ventrículos;
* **Onda T:** associada à repolarização ventricular.

A análise dessas componentes permite extrair informações relacionadas à atividade elétrica e ao ritmo cardíaco.

---

## Processamento do sinal

O sinal adquirido pode apresentar diferentes fontes de interferência, como:

* ruído da rede elétrica;
* interferência eletromagnética;
* artefatos de movimento;
* ruído proveniente do contato dos eletrodos;
* componentes de alta frequência;
* deslocamento da linha de base.

Dessa forma, o processamento do sinal inclui técnicas de filtragem e condicionamento destinadas a melhorar a relação sinal-ruído e facilitar a identificação das características relevantes do ECG.

---

## Equipe

* **Arnaldo Kokke de Brito**
* **Gabriel Lyan Barbosa de Assis**
* **Henrique Marques Cruz**
* **Lara Strutz Carvalho**
* **Otavio Henrique Alves da Silva**
* **Rian Rero Lopes Jerico Vieira**


**Universidade Federal de Minas Gerais — UFMG**
**Escola de Engenharia**

---
