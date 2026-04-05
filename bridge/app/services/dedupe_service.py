class DedupeService:
    """内存去重服务，正式版建议换成 Redis + TTL。"""

    def __init__(self):
        self._seen: set[str] = set()

    def check_and_mark(self, request_id: str) -> bool:
        if request_id in self._seen:
            return False
        self._seen.add(request_id)
        return True


dedupe_service = DedupeService()
