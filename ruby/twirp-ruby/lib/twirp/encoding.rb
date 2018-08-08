# Copyright 2018 Twitch Interactive, Inc.  All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may not
# use this file except in compliance with the License. A copy of the License is
# located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# or in the "license" file accompanying this file. This file is distributed on
# an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing
# permissions and limitations under the License.

require 'json'

module Twirp

  module Encoding
    JSON = "application/json"
    PROTO = "application/protobuf"

    class << self

      def decode(bytes, msg_class, content_type)
        case content_type
        when JSON  then msg_class.decode_json(bytes)
        when PROTO then msg_class.decode(bytes)
        else raise ArgumentError.new("Invalid content_type")
        end
      end

      def encode(msg_obj, msg_class, content_type)
        case content_type
        when JSON  then msg_class.encode_json(msg_obj)
        when PROTO then msg_class.encode(msg_obj)
        else raise ArgumentError.new("Invalid content_type")
        end
      end

      def encode_json(attrs)
        ::JSON.generate(attrs)
      end

      def decode_json(bytes)
        ::JSON.parse(bytes)
      end

      def valid_content_type?(content_type)
        content_type == JSON || content_type == PROTO
      end

      def valid_content_types
        [JSON, PROTO]
      end

    end
  end

end
