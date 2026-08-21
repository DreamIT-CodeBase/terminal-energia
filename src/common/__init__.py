"""Shared helpers for the weekly YOLO pipeline steps.

Every component uploads the whole `src/` folder as its snapshot
(`code: ../src`), so the step scripts sit at the root and import from
here as `from common import ...`.
"""
