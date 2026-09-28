# OpenCode MCP 연결부

기준 소스: Nightdawg/Hurricane `a59230bd4d940a19036804ede3bbfcaa00c105fd`.
작업 브랜치: `feature/opencode-mcp`.

2026-09-28 GitHub CLI 로그인 확인 후 [litriggy/Hurricane](https://github.com/litriggy/Hurricane) 포크를 생성했습니다.
GitHub API로 원본 `Nightdawg/Hurricane`의 포크임을 확인했습니다.
포크는 로컬 `origin`, 원본 저장소는 `upstream` 리모트로 등록했습니다.
MCP 구현은 `feature/opencode-mcp` 브랜치에서 관리합니다.

## 추가한 기능

- 실제 클라이언트 UI에 연결되는 로컬 HTTP 서버: `127.0.0.1:18711`.
- 토큰 인증, 요청 크기와 인자 검사, UI 작업 큐, 요청 만료 처리.
- 상태 조회, 주변 대상 조회, 인벤토리 조회, 이동, 우클릭, 메뉴 선택, 중단 도구.
- 재접속 후 이전 명령을 거부하는 세션 ID.
- 이동 시작과 도착 결과 구분, 이동 제한 거리 440 월드 단위, 60초 제한.
- Bun MCP 어댑터, OpenCode 설정, 연결 진단 프로그램.

설치와 사용법은 [mcp/README.md](mcp/README.md)를 참고하세요.
수정한 실행 파일은 `bin/hafen.jar`입니다. `Release/hafen.jar`는 원본 파일입니다.

## 확인한 결과

| 검증 | 결과 |
| --- | --- |
| Java 21 / Ant `bin` 전체 빌드 | 통과, 실행용 JAR와 리소스 생성 |
| TypeScript 타입 검사와 Biome | 통과 |
| MCP SDK 프로토콜 및 HTTP 어댑터 테스트 | 6개 통과 |
| Java HTTP 서버 검사 | 13개 통과 |
| 실제 UI를 화면 없이 구동한 뒤 stdio MCP 접속 | 도구 7개 발견, `get_state` 정상 응답 |
| 로그인 전 이동 요청 | `not_in_game` 오류로 거부 |
| 설치된 OpenCode의 `opencode mcp list` | `hurricane connected` |
| 소스 diff 공백 검사 | 통과 |

실제 UI 조회 응답:

```json
{"connected":false}
```

HTTP 검사는 잘못된 토큰·JSON·인자·명령·요청 크기·브라우저 Origin 거부,
UI 실행 스레드, 단일 실행, UI 타임아웃, 만료된 명령의 지연 실행 방지를 확인했습니다.
MCP 테스트는 SDK 핸드셰이크, 인증된 명령 전달, 인자 검사, 오류 전달, 자동 재시도 금지,
기본 조회 인자를 확인합니다. 게임 서버 응답을 흉내 낸 이동 성공 검사는 사용하지 않았습니다.

## 검증되지 않은 부분과 환경 제약

- 게임 계정으로 로그인하지 않았으므로 실제 캐릭터 이동, 메뉴 선택, 재접속 후 세션 거부는 미검증입니다.
- 이 Apple Silicon Mac에서는 기본 LWJGL 렌더러가 OpenGL 메인 스레드 검사에서 종료됐습니다.
  JOGL로 변경하면 포함된 네이티브 라이브러리가 Intel용이라 로드되지 않았습니다.
  화면 없는 실제 UI로 연결부는 검증했지만, 이 환경에서의 그래픽 실행 문제는 남아 있습니다.
- 원본 소스는 Java 21 컬렉션 API를 사용하므로 Java 17 빌드가 실패합니다. Java 21로 빌드·실행해야 합니다.
- 전체 빌드에서 원본 코드의 unchecked/deprecation 경고 15개가 발생했습니다. 새 연결부에는 경고가 없었습니다.
- OpenCode의 기존 `pencil` MCP 설정은 실행 파일이 없어 실패했습니다. 새 `hurricane` 연결은 성공했습니다.
- 스킬 부속 검사기는 TypeScript 7 API를 요구해 이 프로젝트의 TypeScript 5.9에서 실행되지 않았습니다.
  프로젝트의 실제 `tsc`와 Biome 검사는 통과했습니다. Java LSP는 설치하지 않고 실제 컴파일로 검사했습니다.

새 연결부 파일은 책임별로 나눴으며 모두 주석과 빈 줄을 제외하면 130줄 미만입니다.
원본의 큰 UI 파일은 연결 시작·틱·종료 지점만 수정했습니다.
테스트에 쓴 클라이언트 프로세스와 로컬 서버는 검증 후 종료했습니다.
