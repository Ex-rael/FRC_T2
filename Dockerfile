# Imagem do simulador de rede em anel (token ring sobre UDP).
# Build em dois estágios: compila com o JDK, executa só com o JRE.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -d out src/*.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY examples ./examples

# A porta de descoberta/anel é a 6000 (UDP). Cada contêiner tem seu próprio
# namespace de rede, então todos podem escutar na mesma porta 6000 — exatamente
# como 4 máquinas distintas na mesma LAN.
EXPOSE 6000/udp

# O apelido/arquivo de config é definido por contêiner no docker-compose.yml.
ENTRYPOINT ["java", "-cp", "out", "Main"]
