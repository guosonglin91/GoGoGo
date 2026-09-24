from dataclasses import dataclass


class ManifestError(ValueError):
    pass


@dataclass(frozen=True)
class Bct1Manifest:
    schema_version: str
    session_id: str
    execution_path: str
    target_cadence_spm: int
    duration_s: int
    official_start_s: int
    official_end_s: int
    android_build_fingerprint: str
    runnerprobe_package: str
    runnerprobe_source_commit: str
    aut_package: str
    aut_version: str
    aut_observation: str

    @classmethod
    def from_dict(cls, data):
        required = (
            "schema_version",
            "session_id",
            "execution_path",
            "target_cadence_spm",
            "duration_s",
            "official_start_s",
            "official_end_s",
            "android_build_fingerprint",
            "runnerprobe_package",
            "runnerprobe_source_commit",
            "aut_package",
            "aut_version",
            "aut_observation",
        )
        missing = [key for key in required if key not in data]
        if missing:
            raise ManifestError("missing required keys: " + ", ".join(missing))

        if data["schema_version"] != "bct1-1":
            raise ManifestError("unsupported schema_version")
        if not isinstance(data["session_id"], str) or not data["session_id"].strip():
            raise ManifestError("invalid session_id")
        if data["execution_path"] not in ("findx8_stock", "aosp_emulator"):
            raise ManifestError("invalid execution_path")
        if data["target_cadence_spm"] not in (150, 165, 180):
            raise ManifestError("invalid target_cadence_spm")
        if data["duration_s"] != 90:
            raise ManifestError("duration_s must be 90")
        if data["official_start_s"] != 30 or data["official_end_s"] != 90:
            raise ManifestError("official window must be [30, 90)")
        if data["aut_observation"] not in (
            "observed", "not_observed", "environment_unsupported"
        ):
            raise ManifestError("invalid aut_observation")

        for key in (
            "android_build_fingerprint",
            "runnerprobe_package",
            "runnerprobe_source_commit",
            "aut_package",
            "aut_version",
        ):
            if not isinstance(data[key], str):
                raise ManifestError(f"{key} must be a string")

        return cls(**{key: data[key] for key in required})
