# 같은 세션에서 기억하며 플레이하기

OpenCode V2용 프로젝트 설정과 `player` 에이전트가 포함되어 있습니다.
Windows 실행기는 `scripts/Hurricane-MCP.cmd`와 `scripts/OpenCode-Hurricane.cmd`입니다.
현재 Windows 설치의 Android Studio JBR 경로와 기존 `bin/.bridge-token.dpapi`를 사용합니다.
다른 PC에서는 Java 경로와 토큰 설정이 필요합니다. 인증 파일은 저장소에 포함하지 않습니다.
Hurricane 실행기는 실행 중인 클라이언트가 없을 때 더 최신인 `build/hafen.jar`를 백업 후 반영합니다.
프로젝트 루트에서 기존 Hurricane 실행 환경과 같은 토큰/포트를 사용해 OpenCode를 실행하세요.
기존 세션은 저장된 에이전트를 유지하므로 에이전트를 `player`로 선택하고 `/mcps`에서 Hurricane을 재연결하세요.
새 세션은 기본적으로 player를 사용합니다. 코드 작업은 build 에이전트를 선택하세요.

첫 지시 예시:

> 기억 프로필은 world-name/my-character야. 현재 상태와 이전 기록을 확인하고,
> 내 마을과 농사를 준비해줘. 우선 20분 동안 채집과 기본 제작을 진행하고,
> 중요한 진행만 알려줘. 기록을 갱신하면서 같은 세션에서 계속해.

월드/캐릭터 이름은 실제 사용하는 이름으로 바꾸세요. 프로필은 수동 식별자이며 게임 계정과 자동 대조하지 않습니다.
다른 캐릭터로 전환했다면 반드시 알려주세요. 여러 자동화가 같은 캐릭터를 동시에 조작하지 않게 하세요.

## 저장 방식

- `.play-memory/play.sqlite`에 WAL 트랜잭션으로 저장하며 Git에서 제외합니다.
- `memory_select`: 프로필 선택, checkpoint와 최근 기록 반환. 프로세스 재연결 후 다시 선택해야 합니다.
- `memory_read`: 기본 응답은 메모 5개와 이벤트 10개의 길이가 제한된 발췌입니다.
  query로 메모 검색, offset/next_note_offset으로 메모 페이지, before로 과거 이벤트를 조회합니다.
  `{"key":"current","include_events":false}`로 current 원문과 revision만 읽으세요.
  body_truncated 메모는 해당 key로 원문을 읽어야 하며, 잘린 발췌로 덮어쓰면 안 됩니다.
  이벤트의 truncated는 응답 발췌 여부입니다. 전체 원문은 로컬 DB에 유지됩니다.
- `memory_save`: current/knowledge/skill 메모 저장. revision 충돌 시 오래된 덮어쓰기를 거절합니다.
- 모든 게임 도구의 검증된 입력은 HTTP 전송 전에 pending으로 저장됩니다.
  응답은 response/rejected, 전송·해석 실패는 unknown으로 남습니다. 토큰/HTTP 헤더는 저장하지 않습니다.
- response는 게임 성공을 의미하지 않습니다. 프로세스가 죽어 남은 pending도 실패를 뜻하지 않습니다.
  최신 게임 상태로 결과를 대조해야 합니다. 자동 재시도는 하지 않습니다.
- 메모는 Markdown 문자열이지만 저장소는 SQLite입니다. 별도 MD 파일 동기화는 하지 않습니다.
  메모를 갱신해도 모델 문맥이 저절로 바뀌지 않으므로 player는 다음 계획 전 다시 읽습니다.
- 기록은 로컬에 계속 쌓이며 자동 삭제하지 않습니다. 삭제/백업은 MCP 프로세스 종료 후 DB와 관련 WAL 파일을 함께 다루세요.

기억 도구 3개는 `HURRICANE_MEMORY_DIR`를 설정한 MCP에서만 활성화됩니다.
게임 클라이언트 재빌드는 필요 없지만, 설치된 클라이언트 버전이 제공하는 게임 도구만 사용할 수 있습니다.
이 기능은 별도 supervisor, 실시간 위험 감지, 단일 제어권 잠금, 작업 완료 자동 판정기를 구현하지 않습니다.
OpenCode가 턴을 끝내면 같은 대화에서 "기록 읽고 계속해"로 이어갈 수 있습니다.

검증: `pnpm --dir mcp check`, `pnpm --dir mcp test`.
