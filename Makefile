# Makefile do simulador de rede em anel (token ring sobre UDP)

JAVAC = javac
JAVA  = java
SRC   = $(wildcard src/*.java)
OUT   = out

.PHONY: all clean run run-a run-b run-c

all: $(OUT)/.built

$(OUT)/.built: $(SRC)
	@mkdir -p $(OUT)
	$(JAVAC) -d $(OUT) $(SRC)
	@touch $(OUT)/.built
	@echo "Compilado em $(OUT)/"

clean:
	rm -rf $(OUT)

# Execução genérica: make run CONF=examples/config_A.txt PORT=6000 \
#                              TARGETS="127.0.0.1:6000-6002"
run: all
	$(JAVA) -cp $(OUT) Main $(CONF) $(PORT) "$(TARGETS)"

# Atalhos para a demonstração local com 3 máquinas (abra 3 terminais):
run-a: all
	$(JAVA) -cp $(OUT) Main examples/config_A.txt 6000 "127.0.0.1:6000-6002"
run-b: all
	$(JAVA) -cp $(OUT) Main examples/config_B.txt 6001 "127.0.0.1:6000-6002"
run-c: all
	$(JAVA) -cp $(OUT) Main examples/config_C.txt 6002 "127.0.0.1:6000-6002"
