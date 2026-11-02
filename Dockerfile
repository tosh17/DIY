FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/install/diy/ /app/
ENV HOST=0.0.0.0
ENV PORT=8092
ENV OLLAMA_URL=http://10.10.1.101:11434
ENV MODEL_BASE=qwen2.5:3b
ENV MODEL_TUNED=diy-parts:q4
EXPOSE 8092
ENTRYPOINT ["/app/bin/diy"]
