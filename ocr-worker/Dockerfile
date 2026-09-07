FROM python:3.12.7-slim-bookworm

ARG TESSERACT_VERSION=5.3.0-2
ARG TESSDATA_VERSION=1:4.1.0-2

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OCR_TESSDATA_VERSION=debian-tessdata-4.1.0-2

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        tesseract-ocr=${TESSERACT_VERSION} \
        tesseract-ocr-eng=${TESSDATA_VERSION} \
        tesseract-ocr-kor=${TESSDATA_VERSION} \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system ocr \
    && useradd --system --gid ocr --create-home --home-dir /home/ocr ocr

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --requirement requirements.txt
COPY app/main.py ./app/main.py

USER ocr
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log"]
