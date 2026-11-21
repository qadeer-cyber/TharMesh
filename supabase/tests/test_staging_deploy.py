"""Regression tests for scripts/staging_deploy.py with a mocked Management API.

No network, no hosted writes. Every API interaction is injected.
"""
import json, os, sys, tempfile, unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import staging_deploy as sd  # noqa: E402

STAGING = sd.STAGING_REF
PRODUCTION = sd.PRODUCTION_REF


def ok_rows(rows=None):
    return sd.Outcome(True, rows=rows if rows is not None else [])


def empty_history(ref, token, timeout=60):
    return ok_rows([])


def pristine_probe(ref, payload, token, timeout=180):
    if payload.get("read_only"):
        return ok_rows([{"public_tables": 0, "sm": 0, "app_functions": 0}])
    return ok_rows([])


class TransportClassification(unittest.TestCase):
    """Item A: only a proven success counts as success."""

    def _curl(self, stdout, returncode=0, stderr=""):
        proc = mock.Mock(returncode=returncode, stdout=stdout, stderr=stderr)
        with mock.patch.object(sd.subprocess, "run", return_value=proc):
            return sd.curl_transport(STAGING, {"query": "select 1"}, "tok")

    def test_http_500_is_failure(self):
        r = self._curl('{"message":"boom"}\n500')
        self.assertFalse(r.ok); self.assertIn("HTTP 500", r.detail)

    def test_http_200_with_message_is_failure(self):
        r = self._curl('{"message":"Failed to run sql query: ERROR: 42601"}\n200')
        self.assertFalse(r.ok); self.assertIn("SQL error", r.detail)

    def test_http_200_non_json_is_failure(self):
        r = self._curl("<html>gateway</html>\n200")
        self.assertFalse(r.ok); self.assertIn("non-JSON", r.detail)

    def test_http_200_unexpected_shape_is_failure(self):
        r = self._curl('{"unexpected":true}\n200')
        self.assertFalse(r.ok); self.assertIn("unexpected response shape", r.detail)

    def test_missing_status_is_uncertain_failure(self):
        r = self._curl("partial body with no status")
        self.assertFalse(r.ok); self.assertTrue(r.uncertain)

    def test_curl_timeout_is_uncertain(self):
        r = self._curl("", returncode=28, stderr="Operation timed out")
        self.assertFalse(r.ok); self.assertTrue(r.uncertain)

    def test_curl_dns_failure_is_not_uncertain(self):
        r = self._curl("", returncode=6, stderr="Could not resolve host")
        self.assertFalse(r.ok); self.assertFalse(r.uncertain)

    def test_success_is_a_json_array(self):
        r = self._curl('[{"x":1}]\n200')
        self.assertTrue(r.ok); self.assertEqual(r.rows, [{"x": 1}])


class TargetIsolation(unittest.TestCase):
    """Item B: staging is the only possible target."""

    def test_transport_refuses_production(self):
        with self.assertRaises(sd.DeploymentError):
            sd.curl_transport(PRODUCTION, {"query": "select 1"}, "tok")

    def test_transport_refuses_any_other_ref(self):
        with self.assertRaises(sd.DeploymentError):
            sd.curl_transport("someotherprojectref", {"query": "select 1"}, "tok")

    def test_cli_exposes_no_ref_override(self):
        import argparse
        parser_flags = []
        real_add = argparse.ArgumentParser.add_argument

        def spy(self, *a, **k):
            parser_flags.extend([x for x in a if isinstance(x, str) and x.startswith("--")])
            return real_add(self, *a, **k)

        import contextlib, io
        with mock.patch.object(argparse.ArgumentParser, "add_argument", spy):
            with contextlib.redirect_stdout(io.StringIO()), \
                 contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    sd.main(["--help"])
        for flag in parser_flags:
            self.assertNotIn("ref", flag.lower(),
                             f"{flag} could redirect the deployment target")

    def test_deploy_always_uses_the_staging_constant(self):
        seen = []

        def q(ref, payload, token, timeout=180):
            seen.append(ref)
            return pristine_probe(ref, payload, token)

        sd.deploy("tok", query=q, history=lambda r, t, timeout=60: (seen.append(r) or ok_rows([])),
                  log=lambda *a: None)
        self.assertTrue(seen)
        self.assertEqual(set(seen), {STAGING})


class UncertainResponses(unittest.TestCase):
    """Item C: a lost response is never retried."""

    def _run_with_uncertainty(self, history_rows):
        calls = {"query": 0}

        def q(ref, payload, token, timeout=180):
            if payload.get("read_only"):
                return pristine_probe(ref, payload, token)
            calls["query"] += 1
            if calls["query"] == 1:       # the history DDL succeeds
                return ok_rows([])
            return sd.Outcome(False, detail="Operation timed out", uncertain=True)

        def h(ref, token, timeout=60):
            return ok_rows(history_rows if calls["query"] > 1 else [])

        with self.assertRaises(sd.UncertainError) as ctx:
            sd.deploy("tok", query=q, history=h, log=lambda *a: None)
        return str(ctx.exception), calls

    def test_uncertain_commit_is_reported_and_not_retried(self):
        msg, calls = self._run_with_uncertainty([{"version": "202611120000"}])
        self.assertIn("COMMITTED", msg)
        self.assertIn("NOT retrying", msg)
        self.assertEqual(calls["query"], 2, "the migration must be attempted exactly once")

    def test_uncertain_without_commit_is_reported(self):
        msg, _ = self._run_with_uncertainty([])
        self.assertIn("not committed", msg)
        self.assertIn("NOT retrying", msg)


class IntegrityPreflight(unittest.TestCase):
    """Item E: integrity, drift and re-run protection."""

    def test_hash_mismatch_aborts(self):
        manifest = sd.load_manifest()
        manifest["migrations"][3]["sha256"] = "0" * 64
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(manifest, fh); path = fh.name
        try:
            with mock.patch.object(sd, "MANIFEST", path):
                with self.assertRaises(sd.DeploymentError) as c:
                    sd.deploy("tok", query=pristine_probe, history=empty_history,
                              log=lambda *a: None)
            self.assertIn("sha256 mismatch", str(c.exception))
        finally:
            os.unlink(path)

    def test_missing_file_aborts(self):
        manifest = sd.load_manifest()
        manifest["migrations"].append(
            {"file": "202611120099_ghost.sql", "version": "202611120099",
             "name": "ghost", "sha256": "0" * 64})
        manifest["migrations"].pop(0)
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(manifest, fh); path = fh.name
        try:
            with mock.patch.object(sd, "MANIFEST", path):
                with self.assertRaises(sd.DeploymentError) as c:
                    sd.deploy("tok", query=pristine_probe, history=empty_history,
                              log=lambda *a: None)
            self.assertIn("differs from the manifest", str(c.exception))
        finally:
            os.unlink(path)

    def test_wrong_migration_count_aborts(self):
        manifest = sd.load_manifest()
        manifest["migrations"] = manifest["migrations"][:14]
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(manifest, fh); path = fh.name
        try:
            with mock.patch.object(sd, "MANIFEST", path):
                with self.assertRaises(sd.DeploymentError) as c:
                    sd.deploy("tok", query=pristine_probe, history=empty_history,
                              log=lambda *a: None)
            self.assertIn("expected 15", str(c.exception))
        finally:
            os.unlink(path)

    def test_non_empty_history_aborts_second_run(self):
        def h(ref, token, timeout=60):
            return ok_rows([{"version": "202611120000", "name": "base_schema"}])
        with self.assertRaises(sd.DeploymentError) as c:
            sd.deploy("tok", query=pristine_probe, history=h, log=lambda *a: None)
        self.assertIn("refusing to run a second time", str(c.exception))

    def test_schema_drift_aborts(self):
        def q(ref, payload, token, timeout=180):
            if payload.get("read_only"):
                return ok_rows([{"public_tables": 4, "sm": 0, "app_functions": 0}])
            return ok_rows([])
        with self.assertRaises(sd.DeploymentError) as c:
            sd.deploy("tok", query=q, history=empty_history, log=lambda *a: None)
        self.assertIn("not in the expected pristine state", str(c.exception))

    def test_unreadable_history_aborts(self):
        def h(ref, token, timeout=60):
            return sd.Outcome(False, detail="HTTP 503")
        with self.assertRaises(sd.DeploymentError) as c:
            sd.deploy("tok", query=pristine_probe, history=h, log=lambda *a: None)
        self.assertIn("cannot read migration history", str(c.exception))


class SqlFailure(unittest.TestCase):
    def test_sql_error_stops_and_reports_progress(self):
        state = {"n": 0}

        def q(ref, payload, token, timeout=180):
            if payload.get("read_only"):
                return pristine_probe(ref, payload, token)
            state["n"] += 1
            if state["n"] <= 3:          # DDL + two migrations
                return ok_rows([])
            return sd.Outcome(False, detail="SQL error: relation does not exist")

        with self.assertRaises(sd.DeploymentError) as c:
            sd.deploy("tok", query=q, history=empty_history, log=lambda *a: None)
        msg = str(c.exception)
        self.assertIn("FAILED", msg)
        self.assertIn("2 migration(s) applied", msg)
        self.assertIn("No repair or reset attempted", msg)


class SuccessPath(unittest.TestCase):
    def test_all_fifteen_applied(self):
        applied = []

        def q(ref, payload, token, timeout=180):
            if payload.get("read_only"):
                return pristine_probe(ref, payload, token)
            applied.append(payload["query"])
            return ok_rows([])

        rc = sd.deploy("tok", query=q, history=empty_history, log=lambda *a: None)
        self.assertEqual(rc, 0)
        self.assertEqual(len(applied), 16, "history DDL + 15 migrations")
        for sub in applied[1:]:
            self.assertTrue(sub.startswith("begin;"))
            self.assertTrue(sub.rstrip().endswith("commit;"))
            self.assertIn("insert into supabase_migrations.schema_migrations", sub)


class CliHistoryFormat(unittest.TestCase):
    """Item D: the recorded history must match what the CLI writes."""

    # Statement counts observed from a real `supabase db push` run against
    # PostgreSQL 17; split_statements() reproduced all 15 arrays byte for byte.
    CLI_COUNTS = {
        "202611120000": 28, "202611120001": 15, "202611120002": 15, "202611120003": 8,
        "202611120004": 26, "202611120005": 17, "202611120006": 30, "202611120007": 21,
        "202611120008": 12, "202611120009": 20, "202611120010": 17, "202611120011": 9,
        "202611120012": 18, "202611120013": 6, "202611120014": 4,
    }

    def test_statement_counts_match_the_real_cli(self):
        for entry in sd.verify_files(sd.load_manifest()):
            got = len(sd.split_statements(sd.read_text(entry["path"])))
            self.assertEqual(got, self.CLI_COUNTS[entry["version"]],
                             f"{entry['file']}: statement split diverges from the CLI")

    def test_statements_keep_begin_and_commit_without_semicolons(self):
        path = [e for e in sd.verify_files(sd.load_manifest())
                if e["version"] == "202611120001"][0]["path"]
        stmts = sd.split_statements(sd.read_text(path))
        self.assertTrue(stmts[0].endswith("begin"))
        self.assertEqual(stmts[-1], "commit")
        self.assertFalse(any(s.endswith(";") for s in stmts))

    def test_dollar_quoted_bodies_are_not_split(self):
        sql = "create function f() returns int as $$ begin; select 1; return 2; end $$ language plpgsql;\nselect 1;"
        self.assertEqual(len(sd.split_statements(sql)), 2)

    def test_semicolons_in_string_literals_are_not_split_points(self):
        self.assertEqual(len(sd.split_statements("select 'a;b';")), 1)

    def test_version_and_name_come_from_the_filename(self):
        for e in sd.verify_files(sd.load_manifest()):
            self.assertEqual(e["file"], f"{e['version']}_{e['name']}.sql")


class WrapperStripping(unittest.TestCase):
    """Item 6 from the previous round, retained as a regression."""

    def test_refuses_an_unexpected_wrapper(self):
        with self.assertRaises(sd.DeploymentError):
            sd.strip_outer_transaction("begin;\nselect 1;\ncommit;\nselect 2;", "x")

    def test_plpgsql_begin_is_untouched(self):
        sql = "begin;\ncreate function f() returns void as $$\nbegin\n  null;\nend;\n$$ language plpgsql;\ncommit;"
        out = sd.strip_outer_transaction(sql, "x")
        self.assertNotIn("begin;", out.split("$$")[0])
        self.assertIn("\nbegin\n", out)

    def test_file_without_wrapper_is_unchanged(self):
        sql = "create table t(i int);\n"
        self.assertEqual(sd.strip_outer_transaction(sql, "x"), sql)


if __name__ == "__main__":
    unittest.main(verbosity=1)
