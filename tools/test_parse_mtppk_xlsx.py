#!/usr/bin/env python3
"""Focused regression tests for public MTPPK timetable time normalization."""

from parse_mtppk_xlsx import monotonic_stops


def cell(value: str):
    return (None, value)


def test_mixed_midnight_encodings() -> None:
    rows = {
        1: {"B": cell("0.9930555555555556")},  # 23:50
        2: {"B": cell("0.0069444444444444")},  # 00:10, fraction restarted
        3: {"B": cell("1.0208333333333333")},  # 24:30, already absolute
    }
    stops = monotonic_stops(rows, [(1, "A"), (2, "B"), (3, "C")], "B")
    assert [item["time_seconds"] for item in stops] == [85_800, 87_000, 88_200]


def test_small_backwards_branch_is_not_retimed() -> None:
    rows = {
        1: {"C": cell("12:00")},
        2: {"C": cell("11:58")},
        3: {"C": cell("12:05")},
    }
    stops = monotonic_stops(rows, [(1, "A"), (2, "Branch"), (3, "B")], "C")
    assert [item["station"] for item in stops] == ["A", "B"]


if __name__ == "__main__":
    test_mixed_midnight_encodings()
    test_small_backwards_branch_is_not_retimed()
    print("MTPPK parser tests: OK")
