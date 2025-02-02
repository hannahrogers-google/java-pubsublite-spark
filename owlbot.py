# Copyright 2021 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""This script is used to synthesize generated parts of this library."""

import synthtool.languages.java as java

java.common_templates(excludes=[
    # TODO: allow when pubsublite-spark is available in libraries-bom
    'samples/install-without-bom/*',
    '.kokoro/build.sh',
    '.kokoro/presubmit/samples.cfg',
    '.kokoro/nightly/samples.cfg',
    # TODO: add Java 17 back when Spark fully supports it
    '.github/workflows/ci.yaml',
    'renovate.json',
])
