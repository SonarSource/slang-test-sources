# Copyright (C) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License. See LICENSE in project root for information.

set -e

cd "$BASEDIR"

# BUILDPR can be empty, a PR number, or "<user>/<repo>/<branch>"
if [[ "$BUILDPR" != */*/* && "$BUILDPR" = *[^0-9]* ]]; then
  failwith "\$BUILDPR should be a number, got: \"$BUILDPR\""
fi

T=""
_get_T() {
  if [[ -z "$T" ]]; then
    T="$(__ az keyvault secret show --vault-name mmlspark-keys --name github-auth \
         | jq -r ".value" | base64 -d)"
  fi
}

declare -gA api_cache
api() {
  local repo="Azure/mmlspark" outvar=""
  while [[ "x$1" = x-* ]]; do case "x$1" in
    ( "x-v" ) outvar="$2"; shift 2 ;;
    ( "x-r" ) repo="$2"  ; shift 2 ;;
    ( * ) failwith "Internal error" ;;
  esac; done
  local call="$1"; shift
  local curlargs=() x use_cache=1 json="" out=""
  while (($# > 0)); do
    x="$1"; shift
    if [[ "$x" = "-" ]]; then break; else use_cache=0; curlargs+=("$x"); fi;
  done
  local key="${repo} ${call} ${curlargs[*]}"
  if ((use_cache)); then json="${api_cache["$key"]}"; fi
  if [[ -z "$json" ]]; then
    _get_T
    json="$(curl --silent --show-error -H "AUTHORIZATION: bearer ${T#*:}" \
                 "https://api.github.com/repos/$repo/$call" \
                 "${curlargs[@]}")"
    if ((use_cache)); then api_cache["$key"]="$json"; fi
  fi
  if (($# == 0)); then out="$json"; else out="$(jq -r "$@" <<<"$json")"; fi
  if [[ -z "$outvar" ]]; then echo "$out"
  else printf -v "$outvar" "%s" "$out"; fi
}

jsonq() { # text...; quotes the text as a json string
  jq --null-input --arg txt "$*" '$txt'
}

VURL="${SYSTEM_TASKDEFINITIONSURI%/}/$SYSTEM_TEAMPROJECT"
VURL+="/_build/index?buildId=$BUILD_BUILDID&_a=summary"
GURL="" SHA1="" REPO="" REF=""

get_pr_info() {
  if [[ "$SHA1" != "" ]]; then return; fi
  if [[ "$BUILDPR" = */*/* ]]; then # branch builds
    local b="$BUILDPR"
    REPO="${b%%/*}"; b="${b#*/}"; REPO="$REPO/${b%%/*}"; REF="${b#*/}"
    GURL="https://github.com/$REPO/tree/$REF"
    api -v SHA1 -r "$REPO" "git/refs/heads/$REF" - '.object.sha // empty'
    if [[ -z "$SHA1" ]]; then failwith "no such repo/ref: $REPO/$REF"; fi
  else # plain pr builds
    local STATE; api -v STATE "pulls/$BUILDPR" - '.state'
    if [[ "$STATE" != "open" ]]; then failwith "PR#$BUILDPR is not open"; fi
    api -v SHA1 "pulls/$BUILDPR" - '.head.sha // empty'
    if [[ -z "$SHA1" ]]; then failwith "no such PR: $BUILDPR"; fi
    api -v REPO "pulls/$BUILDPR" - '.head.repo.full_name // empty'
    api -v REF  "pulls/$BUILDPR" - '.head.ref // empty'
    api -v GURL "pulls/$BUILDPR" - '.html_url // empty'
  fi
}

# post a status, only on PR builds and if we're running all tests
post_status() { # state text
  if [[ "$BUILDPR" = */*/* ]]; then return; fi
  if [[ "$TESTS" != "all" ]]; then return; fi
  local status='"context":"build-pr","state":"'"$1"'","target_url":"'"$VURL"'"'
  api "statuses/$SHA1" -d '{'"$status"',"description":'"$(jsonq "$2")"'}' > /dev/null
}

# post a comment with the given text and link to the build; remember its id
post_comment() { # text [more-text...]
  if [[ "$BUILDPR" = */*/* ]]; then return; fi
  local text="[$1]($VURL)"; shift; local more_text="$*"
  if [[ "$more_text" != "" ]]; then text+=$'\n\n'"$more_text"; fi
  api "issues/$BUILDPR/comments" -d '{"body":'"$(jsonq "$text")"'}' - '.id' \
      > "$PRDIR/comment-id"
}

# delete the last posted comment
delete_comment() {
  if [[ "$BUILDPR" = */*/* ]]; then return; fi
  if [[ ! -r "$PRDIR/comment-id" ]]; then return; fi
  api "issues/comments/$(< "$PRDIR/comment-id")" -X DELETE
}
