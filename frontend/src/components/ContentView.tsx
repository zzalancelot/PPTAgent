import { Card, Col, Divider, Empty, Row, Space, Tag, Typography } from "antd";
import { SoundOutlined } from "@ant-design/icons";
import type { SlideContent, SlideDeckContent } from "../api";
import { modelColor, slideTypeColor, slideTypeLabel } from "../slideType";

const { Title, Paragraph, Text } = Typography;

function SlideCard({ slide }: { slide: SlideContent }) {
  return (
    <Card
      className="slide-card"
      size="small"
      variant="outlined"
      title={
        <Space size={8} wrap>
          <Tag color="default">#{slide.index}</Tag>
          <Tag color={slideTypeColor(slide.slideType)}>{slideTypeLabel(slide.slideType)}</Tag>
        </Space>
      }
    >
      <Title level={5} style={{ marginTop: 0, marginBottom: slide.subtitle ? 2 : 10 }}>
        {slide.title}
      </Title>
      {slide.subtitle ? (
        <Paragraph type="secondary" style={{ marginBottom: 10 }}>
          {slide.subtitle}
        </Paragraph>
      ) : null}

      {slide.bullets?.length ? (
        <ul className="beat-list">
          {slide.bullets.map((b, i) => (
            <li key={i}>{b}</li>
          ))}
        </ul>
      ) : null}

      {slide.bodyText ? (
        <Paragraph style={{ whiteSpace: "pre-wrap", marginTop: 8 }}>{slide.bodyText}</Paragraph>
      ) : null}

      {slide.speakerNotes ? (
        <>
          <Divider style={{ margin: "10px 0" }} dashed />
          <Text type="secondary" style={{ fontSize: 12 }}>
            <SoundOutlined /> 讲者备注：{slide.speakerNotes}
          </Text>
        </>
      ) : null}
    </Card>
  );
}

export default function ContentView({ deck }: { deck: SlideDeckContent }) {
  const { meta, slides } = deck;
  const ordered = [...slides].sort((a, b) => a.index - b.index);

  return (
    <Space direction="vertical" size={16} style={{ width: "100%" }}>
      <Card variant="borderless">
        <Title level={4} style={{ marginTop: 0 }}>
          {meta.topic}
        </Title>
        <Space size={[8, 8]} wrap>
          <Tag color="blue">共 {meta.slideCount} 页</Tag>
          <Tag>{meta.language}</Tag>
          <Divider type="vertical" />
          <Text type="secondary">各章节模型：</Text>
          {Object.entries(meta.modelsUsed).map(([section, model]) => (
            <Tag key={section} color={modelColor(model)}>
              {section} · {model}
            </Tag>
          ))}
        </Space>
      </Card>

      {ordered.length ? (
        <Row gutter={[16, 16]}>
          {ordered.map((slide) => (
            <Col key={slide.index} xs={24} md={12} xl={8}>
              <SlideCard slide={slide} />
            </Col>
          ))}
        </Row>
      ) : (
        <Empty description="没有生成任何页面" />
      )}
    </Space>
  );
}
