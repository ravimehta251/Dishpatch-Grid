FROM python:3.13-alpine
RUN addgroup -S receiver && adduser -S receiver -G receiver && mkdir /data && chown receiver:receiver /data
WORKDIR /app
COPY receiver.py .
USER receiver
ENV PYTHONUNBUFFERED=1 RECEIVER_DB=/data/receiver.sqlite3
EXPOSE 8090
CMD ["python", "receiver.py"]
