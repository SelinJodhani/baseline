# Ojas Bridge = your ZeppBridge runtime image + Python + one script.
# Build it on the machine that already runs ZeppBridge:
#   docker build --build-arg ZEPPBRIDGE_IMAGE=zeppbridge:local -t ojas-bridge:local bridge/
ARG ZEPPBRIDGE_IMAGE=zeppbridge:local
FROM ${ZEPPBRIDGE_IMAGE}

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY ojas_bridge.py /usr/local/bin/ojas_bridge.py

USER zeppbridge
ENV ZEPPBRIDGE_DATA_DIR=/data OJAS_PORT=8787 PYTHONUNBUFFERED=1
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s \
  CMD python3 -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/healthz' % os.environ.get('OJAS_PORT','8787'), timeout=4)"
ENTRYPOINT ["python3", "/usr/local/bin/ojas_bridge.py"]
