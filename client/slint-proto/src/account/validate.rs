//! Input checks ported from Kotlin `validatePlayerName`, `displayLength`,
//! `SettingsService.validateProfileChange` and the register and reset screens.

/// Kotlin `VALID_PLAYER_NAME_REGEX`, as intended: letters, digits, `_` and CJK ideographs.
/// The Kotlin pattern writes the supplementary ranges as ` 0`, which Java reads as
/// ` ` followed by `0`, so it accidentally accepts many symbols; the server checks again.
fn name_char(c: char) -> bool {
    let cp = c as u32;
    c.is_ascii_alphanumeric()
        || c == '_'
        || [
            (0x4E00, 0x9FFF),
            (0x3400, 0x4DBF),
            (0x20000, 0x2A6DF),
            (0x2A700, 0x2B73F),
            (0x2B740, 0x2B81F),
            (0x2B820, 0x2CEAF),
            (0xF900, 0xFAFF),
            (0x2F800, 0x2FA1F),
        ]
        .iter()
        .any(|&(start, end)| (start..=end).contains(&cp))
}

/// Kotlin `Int.isWideCodePoint`.
fn wide(cp: u32) -> bool {
    const WIDE: [(u32, u32); 26] = [
        (0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0x20000, 0x2A6DF), (0x2A700, 0x2B73F), (0x2B740, 0x2B81F), (0x2B820, 0x2CEAF),
        (0xAC00, 0xD7A3), (0x1100, 0x11FF), (0x3130, 0x318F),
        (0x3040, 0x309F), (0x30A0, 0x30FF), (0x31F0, 0x31FF), (0x1B000, 0x1B0FF),
        (0x3100, 0x312F), (0x31A0, 0x31BF),
        (0xFF01, 0xFF60), (0xFFE0, 0xFFE6),
        (0x3200, 0x32FF), (0x3300, 0x33FF), (0xF900, 0xFAFF), (0x2F800, 0x2FA1F),
        (0x1F300, 0x1F64F), (0x1F680, 0x1F6FF), (0x1F900, 0x1F9FF), (0x1FA70, 0x1FAFF), (0x2600, 0x27BF),
    ];
    WIDE.iter().any(|&(start, end)| (start..=end).contains(&cp))
}

/// Kotlin `String.displayLength`: a wide character counts as two.
pub fn display_length(text: &str) -> usize {
    text.chars().map(|c| if wide(c as u32) { 2 } else { 1 }).sum()
}

/// Kotlin `String.length`: UTF-16 code units.
fn utf16_len(text: &str) -> usize {
    text.encode_utf16().count()
}

/// Kotlin `validatePlayerName`.
pub fn player_name(name: &str) -> Result<(), String> {
    if name.is_empty() || !name.chars().all(name_char) {
        return Err("昵称只能包含字母数字汉字_".into());
    }
    let len = display_length(name);
    if !(3..=24).contains(&len) {
        return Err(format!("昵称长度应在3~24，当前为{len}（一个汉字算两个）"));
    }
    Ok(())
}

fn blank(text: &str) -> bool {
    text.trim().is_empty()
}

/// RegisterScreen `validateRegisterInput`, in the same order.
pub fn register(name: &str, qq: &str, pwd: &str, pwd2: &str) -> Result<(), String> {
    if pwd != pwd2 {
        return Err("两次输入的密码不一致".into());
    }
    if blank(name) || blank(qq) || blank(pwd) {
        return Err("未填写完整".into());
    }
    player_name(name)
}

/// ResetPasswordScreen `validateNewPassword`.
pub fn new_password(pwd: &str, pwd2: &str) -> Result<(), String> {
    if blank(pwd) || blank(pwd2) {
        return Err("未填写完整".into());
    }
    if pwd != pwd2 {
        return Err("两次输入的密码不一致".into());
    }
    if !(6..=16).contains(&utf16_len(pwd)) {
        return Err("密码长度须在6~16个字符".into());
    }
    Ok(())
}

/// ResetPasswordScreen `generateQqMailOperation`: the trimmed QQ number, 5-10 digits.
pub fn qq(qq: &str) -> Result<String, String> {
    let qq = qq.trim();
    if !(5..=10).contains(&qq.len()) || !qq.bytes().all(|b| b.is_ascii_digit()) {
        return Err("QQ号格式不正确".into());
    }
    Ok(qq.to_owned())
}

/// `SettingsService.validateProfileChange`.
pub fn profile_change(name: &str, pwd: &str, current_name: &str) -> Result<(), String> {
    let bytes = name.len();
    if !(3..=24).contains(&bytes) {
        return Err(format!("昵称须在3~24个字节，当前为{bytes}"));
    }
    if !pwd.is_empty() && !(6..=16).contains(&utf16_len(pwd)) {
        return Err("密码长度须在6~16个字符".into());
    }
    if name == current_name && pwd.is_empty() {
        return Err("没有修改内容".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_count_wide_characters_twice() {
        assert_eq!(display_length("ab汉字"), 6);
        assert!(player_name("玩家_01").is_ok());
        assert!(player_name("ab").unwrap_err().contains("当前为2"));
        assert!(player_name("汉").unwrap_err().contains("当前为2"));
        assert!(player_name("汉字").is_ok(), "two ideographs are four wide");
        assert_eq!(player_name("a b c").unwrap_err(), "昵称只能包含字母数字汉字_");
        assert_eq!(player_name("名字@").unwrap_err(), "昵称只能包含字母数字汉字_");
        assert!(player_name(&"a".repeat(25)).is_err());
        assert!(player_name("\u{20000}\u{20001}").is_ok(), "extension B");
    }
    #[test]
    fn register_checks_in_compose_order() {
        assert_eq!(register("", "", "a", "b").unwrap_err(), "两次输入的密码不一致");
        assert_eq!(register("玩家", " ", "pw", "pw").unwrap_err(), "未填写完整");
        assert_eq!(register("a!", "1", "pw", "pw").unwrap_err(), "昵称只能包含字母数字汉字_");
        assert!(register("玩家", "12345", "pw", "pw").is_ok(), "length is the server's check");
    }
    #[test]
    fn new_passwords_and_qq_numbers() {
        assert_eq!(new_password("", "x").unwrap_err(), "未填写完整");
        assert_eq!(new_password("abcdef", "abcdeg").unwrap_err(), "两次输入的密码不一致");
        assert_eq!(new_password("abc", "abc").unwrap_err(), "密码长度须在6~16个字符");
        assert!(new_password("abcdef", "abcdef").is_ok());
        assert_eq!(qq(" 12345 ").unwrap(), "12345");
        assert!(qq("1234").is_err() && qq("12345678901").is_err() && qq("12a45").is_err());
    }
    #[test]
    fn profile_changes() {
        assert_eq!(profile_change("ab", "", "x").unwrap_err(), "昵称须在3~24个字节，当前为2");
        assert_eq!(profile_change("玩家", "", "x").unwrap(), (), "six UTF-8 bytes");
        assert_eq!(profile_change("abc", "12", "x").unwrap_err(), "密码长度须在6~16个字符");
        assert_eq!(profile_change("abc", "", "abc").unwrap_err(), "没有修改内容");
        assert!(profile_change("abc", "123456", "abc").is_ok());
    }
}
