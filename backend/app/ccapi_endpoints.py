"""Canon CCAPI endpoint constants.

Everything that depends on the exact CCAPI URL layout lives here so it is easy
to adjust if Canon changes path names or you need to target a different API
version. The official reference (Canon Camera Control API) is the source of
truth for these strings.

The CCAPI root (`GET /ccapi`) advertises every endpoint the connected camera
actually supports, grouped by API version, e.g.:

    {
      "ver100": [ {"path": "/ccapi/ver100/deviceinformation", "get": true}, ... ],
      "ver110": [ {"path": "/ccapi/ver110/contents", "get": true}, ... ]
    }

Rather than hard-coding one version, the client discovers the real path from
the root document and only falls back to the constants below when discovery
is not possible. Keep the fallbacks in "best version first" order.
"""

# Root document. Always unversioned.
ROOT = "/ccapi"

# --- Fallback paths, newest version first ---------------------------------
# These are only used when endpoint discovery from the root document fails.

DEVICE_INFORMATION = [
    "/ccapi/ver100/deviceinformation",
]

# Storage device status (free/total space, name, access state).
DEVICE_STATUS_STORAGE = [
    "/ccapi/ver110/devicestatus/storage",
    "/ccapi/ver100/devicestatus/storage",
]

# File-system "contents" tree. This is the entry point for browsing the card.
#   GET <contents>                         -> list of storages
#   GET <contents>/<storage>               -> list of directories
#   GET <contents>/<storage>/<dir>         -> list of files (paginated)
#   GET <contents>/<storage>/<dir>/<file>  -> download the file
CONTENTS = [
    "/ccapi/ver130/contents",
    "/ccapi/ver120/contents",
    "/ccapi/ver110/contents",
    "/ccapi/ver100/contents",
]

# --- Query parameters for the contents directory listing ------------------
# GET <dir>?kind=number          -> {"contentsnumber": N, "pagenumber": P}
# GET <dir>?type=all&page=<n>    -> {"path": [ ...file urls... ]}
# GET <file>?kind=info           -> {"name","filesize","lastmodifieddate",...}
PARAM_KIND = "kind"
PARAM_TYPE = "type"
PARAM_PAGE = "page"

KIND_NUMBER = "number"   # ask for counts + page count instead of the list
KIND_INFO = "info"       # ask for a single file's metadata
KIND_LIST = "list"       # explicit "give me the path list" (default behaviour)
KIND_MAIN = "main"       # full-resolution original (default download)
KIND_THUMBNAIL = "thumbnail"
KIND_DISPLAY = "display"

TYPE_ALL = "all"

# Default RAW extension this app cares about.
RAW_EXTENSIONS = (".cr3",)
