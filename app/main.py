import logging

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import (
    ALLOWED_ORIGINS,
    ANNOTATED_CONTAINER,
    SOURCE_CONTAINER,
)

from app.fabric import close_lakehouse_writer

from app.routers.annotations import router as annotations_router
from app.routers.devices import router as devices_router
from app.routers.files import router as files_router
from app.routers.files import health_router as fabric_health_router
from app.routers.labelling import router as labelling_router
from app.routers.videos import router as videos_router


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

# The Azure SDKs log every request and response header at INFO, which buries
# our own lines and dumps a screenful per upload. Warnings still come through.
for _noisy in (
    "azure.core.pipeline.policies.http_logging_policy",
    "azure.identity",
):
    logging.getLogger(_noisy).setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):

    yield

    # The lakehouse client holds a cached credential and an HTTP pool.
    close_lakehouse_writer()


app = FastAPI(
    title="Labelling System API",
    version="2.0.0",
    description=(
        "Frames are captured from a source video on request, boards "
        "are labelled on those frames and price regions inside each "
        "board, and one submit files the video into that week as a "
        "YOLO dataset. Also stores JSON documents in the Microsoft "
        "Fabric lakehouse."
    ),
    lifespan=lifespan,
)


app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# The two video routers share the /videos prefix: one reads source data,
# the other reads and writes annotations.
app.include_router(videos_router)
app.include_router(annotations_router)

# Labelling: capture frames from the video, then submit the whole video once.
app.include_router(labelling_router)

# Device API: token auth plus brokered SAS for artifact upload and model
# download, so the storage account key never ships in the APK. Deliberately
# separate from the readings endpoint the business payload uses.
app.include_router(devices_router)

# Fabric lakehouse: JSON documents partitioned by vehicle and date.
app.include_router(files_router)
app.include_router(fabric_health_router)


@app.get("/", tags=["System"])
def root():
    return {
        "service": "Labelling System API",
        "version": app.version,
        "source_container": SOURCE_CONTAINER,
        "annotated_container": ANNOTATED_CONTAINER,
        "documentation": "/docs",
    }


@app.get("/health", tags=["System"])
def health():
    return {"status": "healthy"}
