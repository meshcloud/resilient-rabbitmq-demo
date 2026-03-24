#!/bin/bash
cd "$(dirname "$0")"
npx @marp-team/marp-cli presentation.md --output presentation.html
